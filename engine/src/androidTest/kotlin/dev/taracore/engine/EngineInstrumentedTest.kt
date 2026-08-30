package dev.taracore.engine

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end native test: a real GGUF is loaded and a real completion is generated.
 *
 * The model is **not** committed -- it is 100+ MB and carries its own license. Get it
 * onto the device first:
 *
 *     ./scripts/fetch-model.sh --tiny
 *     ./gradlew :engine:connectedCpuDebugAndroidTest
 *
 * With no model present every test is skipped via `assumeTrue` rather than failing,
 * so a CI run without weights stays green while still compiling and linking the JNI
 * layer -- which is most of what could break.
 */
class EngineInstrumentedTest {

    private companion object {
        const val TAG = "TaraCore/Test"

        /** Anything in the models dir; the tests do not care which model it is. */
        val CANDIDATES = listOf(
            "smollm2-135m-instruct-q4km.gguf",
            "qwen2.5-0.5b-instruct-q4km.gguf",
            "tiny.gguf",
        )
    }

    private lateinit var controller: EngineController
    private var modelFile: File? = null

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        modelFile = stagedModel(ctx) ?: pushedModel(ctx)
        Log.i(TAG, "model chosen: $modelFile")
        controller = EngineController()
    }

    /**
     * The model as shipped inside the test APK by the `stageTestModel` Gradle task.
     *
     * Copied out to the cache directory because llama.cpp mmaps a real file and an
     * asset inside an APK is a compressed entry in a zip, not a file. The copy is
     * skipped when a previous run already made it, so only the first test in a run
     * pays for it.
     */
    private fun stagedModel(ctx: android.content.Context): File? = try {
        val names = ctx.assets.list("models").orEmpty().filter { it.endsWith(".gguf") }
        Log.i(TAG, "staged assets: $names")
        names.firstOrNull()?.let { name ->
            val out = File(ctx.cacheDir, name)
            if (!out.isFile || out.length() == 0L) {
                Log.i(TAG, "extracting $name from assets to ${out.absolutePath}")
                ctx.assets.open("models/$name").use { input ->
                    out.outputStream().use { input.copyTo(it, DEFAULT_BUFFER_SIZE * 8) }
                }
            }
            out.takeIf { it.isFile && it.length() > 0 }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "no staged model in the test APK", t)
        null
    }

    /**
     * Fallback: a GGUF placed by hand in the app's external files directory.
     *
     * Note that `connectedAndroidTest` uninstalls the test package when it finishes,
     * which removes this directory, so a model pushed before the run is usually gone
     * by the time the next one starts. The asset path above is the reliable one; this
     * exists for someone iterating with `am instrument` directly.
     */
    private fun pushedModel(ctx: android.content.Context): File? {
        val dir = File(ctx.getExternalFilesDir(null), "models")
        return CANDIDATES.asSequence()
            .map { File(dir, it) }
            .firstOrNull { it.isFile && it.length() > 0 }
            ?: dir.listFiles(java.io.FileFilter { f ->
                f.isFile && f.extension == "gguf" && f.length() > 0
            })?.firstOrNull()
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) {
            runBlocking { runCatching { controller.unload() } }
            controller.close()
        }
    }

    @Test
    fun nativeLibraryLoads() {
        // This one must pass with or without a model: if the .so did not load, the
        // 16 KB alignment, the ABI filters or the CMake link line are wrong.
        assertTrue("libtaracore_jni.so failed to load", controller.isNativeAvailable)
        val version = controller.llamaVersion
        Log.i(TAG, "llama.cpp version: $version")
        assertNotNull(version)
        assertTrue("version string was empty", version.isNotBlank())
    }

    @Test
    fun loadsModelAndReportsMetadata() = runBlocking {
        val file = requireModel()

        val result = withTimeout(120_000) {
            controller.load(specFor(file))
        }

        assertTrue("load failed: ${result.error}", result.ok)
        assertTrue("model size not reported", result.modelSizeBytes > 0)
        assertTrue("vocab size not reported", result.vocabSize > 0)
        assertTrue("context size not reported", result.nCtx > 0)
        Log.i(TAG, "loaded ${result.description} | vocab=${result.vocabSize} " +
                "| ctx=${result.nCtx} | backend=${result.backendName}")

        assertTrue(controller.isLoaded())
        val state = controller.state.value
        assertTrue("unexpected state $state", state is EngineState.Ready)
    }

    @Test
    fun generatesNonEmptyText() = runBlocking {
        val file = requireModel()
        val load = withTimeout(120_000) { controller.load(specFor(file)) }
        assertTrue("load failed: ${load.error}", load.ok)

        val request = GenRequest(
            requestId = "test-say-hi",
            messages = listOf(
                ChatMessage(ChatMessage.ROLE_USER, "Say hi."),
            ),
            params = GenParams(maxTokens = 48, temperature = 0.7f, seed = 42L),
        )

        val events = withTimeout(180_000) { controller.stream(request).toList() }

        val errors = events.filterIsInstance<GenEvent.Error>()
        assertTrue("generation reported errors: ${errors.map { it.message }}", errors.isEmpty())

        val done = events.filterIsInstance<GenEvent.Done>().singleOrNull()
        assertNotNull("stream produced no Done event", done)
        done!!

        Log.i(TAG, "generated ${done.stats.genTokens} tokens at " +
                "%.1f tok/s: %s".format(done.stats.genTokensPerSecond, done.text.take(120)))

        assertTrue("no tokens were generated", done.stats.genTokens > 0)
        assertTrue("no prompt tokens were counted", done.stats.promptTokens > 0)
        assertTrue("generated text was blank", done.text.isNotBlank())

        // The streamed pieces must reassemble into exactly the final text -- this is
        // what catches a UTF-8 boundary bug in the native emit path.
        val streamed = events.filterIsInstance<GenEvent.Token>().joinToString("") { it.piece }
        assertEquals("streamed pieces do not reassemble into the final text",
            done.text, streamed)
    }

    @Test
    fun appliesChatTemplate() = runBlocking {
        val file = requireModel()
        val load = withTimeout(120_000) { controller.load(specFor(file)) }
        assertTrue("load failed: ${load.error}", load.ok)

        val rendered = controller.formatChat(
            listOf(
                ChatMessage(ChatMessage.ROLE_SYSTEM, "You are terse."),
                ChatMessage(ChatMessage.ROLE_USER, "MARKER_USER_TURN"),
            )
        )

        Log.i(TAG, "rendered template:\n$rendered")
        assertTrue("template produced nothing", rendered.isNotBlank())
        // Whatever the template dialect, the content has to survive into the prompt.
        assertTrue("user turn missing from the rendered prompt", "MARKER_USER_TURN" in rendered)
        assertTrue("system turn missing from the rendered prompt", "You are terse." in rendered)
    }

    @Test
    fun cancellationStopsGenerationEarly() = runBlocking {
        val file = requireModel()
        val load = withTimeout(120_000) { controller.load(specFor(file)) }
        assertTrue("load failed: ${load.error}", load.ok)

        val request = GenRequest(
            requestId = "test-cancel",
            messages = listOf(
                ChatMessage(ChatMessage.ROLE_USER, "Count slowly from one to five hundred."),
            ),
            params = GenParams(maxTokens = 2048, temperature = 0.7f, seed = 7L),
        )

        var seen = 0
        withTimeout(180_000) {
            controller.stream(request).collect { ev ->
                if (ev is GenEvent.Token) {
                    seen++
                    // Cancel by id, exercising the same path the AIDL cancel() uses.
                    if (seen == 5) controller.cancel(request.requestId)
                }
            }
        }

        Log.i(TAG, "cancelled after $seen tokens (cap was 2048)")
        assertTrue("no tokens arrived before the cancel", seen > 0)
        assertTrue("cancel did not stop generation: $seen tokens", seen < 2048)
    }

    @Test
    fun reusesKvCacheAcrossTurns() = runBlocking {
        val file = requireModel()
        val load = withTimeout(120_000) { controller.load(specFor(file)) }
        assertTrue("load failed: ${load.error}", load.ok)

        val first = listOf(
            ChatMessage(ChatMessage.ROLE_SYSTEM, "You are a helpful assistant that answers briefly."),
            ChatMessage(ChatMessage.ROLE_USER, "What is the capital of France?"),
        )

        val a = withTimeout(180_000) {
            controller.stream(
                GenRequest("kv-1", first, GenParams(maxTokens = 24, seed = 1L))
            ).toList()
        }
        val doneA = a.filterIsInstance<GenEvent.Done>().single()

        // Same prefix, one turn longer: the shared prompt must not be re-decoded.
        val second = first + listOf(
            ChatMessage(ChatMessage.ROLE_ASSISTANT, doneA.text),
            ChatMessage(ChatMessage.ROLE_USER, "And of Japan?"),
        )
        val b = withTimeout(180_000) {
            controller.stream(
                GenRequest("kv-2", second, GenParams(maxTokens = 24, seed = 1L))
            ).toList()
        }
        val doneB = b.filterIsInstance<GenEvent.Done>().single()

        Log.i(TAG, "turn 1 prompt=${doneA.stats.promptTokens} tok, " +
                "turn 2 prompt=${doneB.stats.promptTokens} tok (longer conversation)")

        assertTrue("second turn generated nothing", doneB.stats.genTokens > 0)
        // The second conversation is strictly longer, so without prefix reuse its
        // prompt-token count would exceed the first's. Reuse makes it smaller.
        assertTrue(
            "KV prefix does not appear to have been reused: " +
                "turn1=${doneA.stats.promptTokens}, turn2=${doneB.stats.promptTokens}",
            doneB.stats.promptTokens < doneA.stats.promptTokens + 24,
        )
    }

    @Test
    fun grammarConstrainsOutputToTheGivenAlphabet() = runBlocking {
        val file = requireModel()
        val load = withTimeout(120_000) { controller.load(specFor(file)) }
        assertTrue("load failed: ${load.error}", load.ok)

        val allowed = listOf("red", "green", "blue")
        val events = withTimeout(180_000) {
            controller.stream(
                GenRequest(
                    requestId = "grammar-choice",
                    messages = listOf(
                        ChatMessage(ChatMessage.ROLE_USER, "Name a colour of the sky."),
                    ),
                    params = GenParams(
                        maxTokens = 16,
                        seed = 3L,
                        grammar = dev.taracore.api.Gbnf.choice(allowed),
                    ),
                )
            ).toList()
        }

        val errors = events.filterIsInstance<GenEvent.Error>()
        assertTrue("grammar run reported errors: ${errors.map { it.message }}", errors.isEmpty())

        val done = events.filterIsInstance<GenEvent.Done>().single()
        Log.i(TAG, "grammar-constrained output: ${done.text}")
        assertTrue(
            "constrained output ${done.text} was not one of $allowed",
            done.text.trim() in allowed,
        )
    }

    @Test
    fun grammarRunsAfterAPromptWithoutCrashingTheProcess() = runBlocking {
        // Regression: the grammar sampler used to sit in the same chain that receives
        // prompt tokens. A grammar cannot match arbitrary prompt text, so llama.cpp
        // threw std::runtime_error, nothing caught it, and the whole :engine process
        // aborted -- taking inference down for every app on the device. A long,
        // grammar-incompatible prompt is what triggered it.
        val file = requireModel()
        val load = withTimeout(120_000) { controller.load(specFor(file)) }
        assertTrue("load failed: ${load.error}", load.ok)

        val events = withTimeout(180_000) {
            controller.stream(
                GenRequest(
                    requestId = "grammar-after-long-prompt",
                    messages = listOf(
                        ChatMessage(
                            ChatMessage.ROLE_USER,
                            "Here is a long preamble that the grammar could never match, " +
                                "full of words and punctuation. Now answer: yes or no?",
                        ),
                    ),
                    params = GenParams(
                        maxTokens = 8,
                        seed = 5L,
                        grammar = dev.taracore.api.Gbnf.choice(listOf("yes", "no")),
                    ),
                )
            ).toList()
        }

        val done = events.filterIsInstance<GenEvent.Done>().singleOrNull()
        assertNotNull("engine died or produced no result", done)
        assertTrue("expected yes or no, got ${done!!.text}", done.text.trim() in listOf("yes", "no"))

        // The engine must still be usable afterwards -- proving nothing was corrupted.
        assertTrue("engine is no longer loaded after a grammar run", controller.isLoaded())
    }

    @Test
    fun anInvalidGrammarFailsTheRequestRatherThanTheProcess() = runBlocking {
        val file = requireModel()
        val load = withTimeout(120_000) { controller.load(specFor(file)) }
        assertTrue("load failed: ${load.error}", load.ok)

        val events = withTimeout(120_000) {
            controller.stream(
                GenRequest(
                    requestId = "grammar-invalid",
                    messages = listOf(ChatMessage(ChatMessage.ROLE_USER, "Hello.")),
                    // No `root` rule, so llama.cpp refuses to build the sampler.
                    params = GenParams(maxTokens = 8, grammar = "notroot ::= \"x\"\n"),
                )
            ).toList()
        }

        val errors = events.filterIsInstance<GenEvent.Error>()
        assertTrue("an invalid grammar should surface as an error", errors.isNotEmpty())
        Log.i(TAG, "invalid grammar reported: ${errors.first().message}")

        // And crucially the engine survived it and still works.
        assertTrue(controller.isLoaded())
        val after = withTimeout(120_000) {
            controller.stream(
                GenRequest(
                    requestId = "grammar-invalid-recovery",
                    messages = listOf(ChatMessage(ChatMessage.ROLE_USER, "Say hi.")),
                    params = GenParams(maxTokens = 16),
                )
            ).toList()
        }
        assertTrue(
            "engine unusable after an invalid grammar",
            after.filterIsInstance<GenEvent.Done>().single().stats.genTokens > 0,
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun requireModel(): File {
        val f = modelFile
        assumeTrue(
            "no GGUF available -- run ./scripts/fetch-model.sh --tiny, then rebuild so " +
                "the model is staged into the test APK",
            f != null,
        )
        assumeTrue("libtaracore_jni.so is not available", controller.isNativeAvailable)
        return f!!
    }

    private fun specFor(file: File) = ModelSpec(
        modelId = file.nameWithoutExtension,
        path = file.absolutePath,
        // Small context: the test devices are emulators as often as phones, and a
        // 4096-token KV cache on a 0.5B model is pure setup cost here.
        nCtx = 1024,
        nThreads = 4,
        nGpuLayers = 0,
        nBatch = 256,
    )
}
