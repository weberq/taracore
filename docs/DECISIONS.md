# Decisions

Running log of choices made where the specification left room, plus every `TODO`
left in the tree and why it is still a `TODO`.

> Scaffolded under the working name *Local AI Core*, renamed to **Tara Core** before
> the first source file was written; the rename is therefore a clean namespace, not a
> refactor, and no `localai` identifiers exist anywhere in the tree.

---

## D1 — Repository directory is `taracore/`, root project name is `tara-core`

The brand spec asks for a `tara-core/` repo directory. The session's working
directory is `taracore/`, so the on-disk directory keeps that name and
`rootProject.name` is `tara-core` as specified. Nothing in the build depends on the
directory name.

## D2 — llama.cpp pinned to tag `b4585`, shallow submodule

Pinned to a specific tag rather than a moving branch so builds are reproducible.
Cloned `--depth 1` because the full history is ~1 GB and nothing in the build reads
it. Bumping the pin is a deliberate, reviewable commit.

## D3 — `arm64-v8a` everywhere, `x86_64` only for emulator debug

`x86_64` is added by the `debug` build type only, guarded so `release` can never
pick it up. Release APKs would otherwise carry a ~40 MB slice no phone can run.

## D4 — CPU arch flags: `armv8.2-a+dotprod+fp16` with an `armv8-a` fallback

`GGML_CPU_ARM_ARCH` is set from a CMake `check_cxx_compiler_flag` probe. Every arm64
Android device since ~2018 supports v8.2 dotprod, but the probe means an older NDK
that rejects the flag still produces a working (slower) build instead of failing.

## D5 — Single global engine, single in-flight generation

`llama.cpp` contexts are not thread-safe and a phone has RAM for one model. The
service therefore owns exactly one `EngineController`, all native calls run on a
dedicated single-thread dispatcher, and a `Mutex` serialises generation. Concurrent
clients queue in `RequestQueue` (FIFO) rather than sharing a context. Multi-sequence
batching is on the roadmap and would change this.

## D6 — KV-cache reuse by longest common prefix

Before each generation the engine compares the new token sequence against the
previous one and keeps the shared prefix in the KV cache, removing only the divergent
tail. This makes multi-turn chat cheap without a full session-management layer. A
mismatch simply clears the cache — correctness never depends on the optimisation.

## D7 — Bearer-token auth on by default for the HTTP server

The server binds `127.0.0.1` only, but any app on the device can reach loopback
without holding a permission. A token generated on first run (32 random bytes,
base64url) is therefore required by default. Users can disable it in Settings, which
the UI labels as unsafe.

## D8 — `specialUse` foreground-service type

None of the platform FGS types describe "hosts a shared inference engine for other
apps". `specialUse` with the subtype string is the honest declaration. Play requires
a written justification for this type — noted in `docs/ARCHITECTURE.md`.

## D9 — Custom permission is `protectionLevel="normal"`

`signature` would restrict Tara Core to apps signed by the same key, defeating the
point of a shared device-wide engine. `dangerous` would force a runtime prompt with
no system-provided UI string that makes sense. `normal` means install-time grant,
visible in app details, which matches the threat model: the danger is compute and
battery use, not data exfiltration from Tara Core itself.

## D10 — Room for the model registry, **multi-process** DataStore for settings

The registry is relational and queried (`by id`, `by family`, `downloaded only`), so
Room, which is safe to open from two processes.

Settings are a flat bag of scalars read as a `Flow`, which says "Preferences
DataStore" — and that is what the first build used. It was wrong, and the failure was
silent. **Preferences DataStore is not multi-process safe:** each process gets its own
instance, its own in-memory cache and its own file watcher, so a write in the UI
process is never observed in `:engine`. On device, toggling "enable the HTTP server"
updated the UI, persisted to disk, and did nothing at all, because the service that
owns the server never heard about it. Nothing logged an error; `curl` just got an
empty reply.

Settings now use `MultiProcessDataStoreFactory` with a JSON serializer over the whole
`SettingsSnapshot`. It coordinates through an exclusive file lock and a shared
counter, so a write in one process invalidates the other's cache and re-emits on its
flow. JSON rather than protobuf because the file is tiny and being able to `cat` it
while debugging a cross-process problem is worth more than the bytes.

The general lesson, recorded because it will come up again: **the process split in D8
is not free.** Anything the UI and the engine both touch has to be explicitly
multi-process safe, and the failure mode is silence rather than an exception.

## D11 — Prompts over 512 KB travel by `ParcelFileDescriptor`

Binder's transaction buffer is 1 MB *shared per process*, so a large prompt in the
parcel can fail for reasons unrelated to the caller. `GenerationRequest` therefore
carries an optional `largePrompt` pipe; `:client-sdk` switches to it automatically.

## D12 — `gpu` flavour falls back to CPU at runtime

The `gpu` flavour compiles the Vulkan backend in, but `ggml` device enumeration can
still find no usable device (no driver, blocklisted GPU, emulator). The engine reports
the backend it actually initialised, and the dashboard shows it, so a silent fallback
is always visible.

---

## D13 — `catalog.json` ships with empty `sha256` fields

`grep -rn "TODO\|FIXME\|XXX" --exclude-dir=third_party` over `.kt`, `.cpp`, `.h`,
`.kts`, `.xml` and `.sh` returns nothing: there are **no `TODO` markers in the tree**.
There is, however, one piece of deliberately unfinished *data*, recorded here so it is
not mistaken for an oversight.

Sixteen of the nineteen catalog entries carry `"sha256": ""`. Three have real
digests — `smollm2-135m-instruct-q4km`, `qwen2.5-0.5b-instruct-q4km` and
`qwen2.5-1.5b-instruct-q4km` — because those were actually downloaded, hashed and run
on a device during development. Their `size_bytes` are exact for the same reason. The digests are not invented, because a
fabricated digest is strictly worse than no digest: it would fail verification on a
correct download and send users hunting a corruption that never happened.
`ModelDownloadWorker` treats an empty digest as "cannot verify", downloads anyway, and
logs a warning that an unverified multi-gigabyte binary is about to be mapped into
memory.

**To close this:** download each catalogued file once, record the real digest, paste it
in. After that the worker verifies as it streams, at no extra cost, and a mismatch
deletes the partial file. Until then the protection is HTTPS to Hugging Face and
nothing more.

`size_bytes` is likewise approximate. It only gates the free-space check; the worker
trusts the server's `Content-Length` for progress and for what it actually writes.

## D14 — unit tests set `isReturnDefaultValues = true`

`android.util.Log` is a throwing stub in the unit-test JAR. The alternatives were a
mocking framework, or an injected logger interface threaded through classes that have
no other reason to want one. These tests exercise queue behaviour and JSON parsing,
where logging is incidental, so returning defaults is the proportionate answer.

## D15 — `:sample-client` carries a `cpu`/`gpu` dimension it does not use

It never touches native code, so the dimension means nothing to it. It is declared so
that a single `./gradlew assembleCpuDebug` builds every module in the repository,
keeping both the acceptance criteria and the CI workflow to one command.

## D16 — every request path goes through `RequestQueue`, HTTP included

The HTTP surface originally called `EngineController.stream` directly. That was
*correct* — the engine's own mutex serialises regardless — but it meant an HTTP
request did not appear in `queueDepth`, was not subject to the capacity limit, and
could overtake a bound client that asked first. `RequestQueue.QueuedRequest` now
carries its own `run` lambda so both callers share one queue without either being
special-cased inside it.

The bug this surfaced is worth recording: `cancelQueued` used to record *any* id it
was handed, and closing an HTTP response cancels by id after the request has already
finished. Every request served would have added one permanent entry to a set. The
queue now tracks which ids are actually waiting, so a late cancel is a no-op rather
than a leak. Covered by a regression test.

## D17 — the instrumented test model ships inside the test APK

The obvious approach is `adb push` into the test app's external files directory. It
does not work, for two compounding reasons:

1. `connectedAndroidTest` **uninstalls the test package** when the run finishes, and
   uninstalling removes `/sdcard/Android/data/<pkg>/`. Anything pushed before the run
   is gone by the time the next one starts.
2. A directory created by `adb push` is owned by `shell`. Under scoped storage the
   app's own sandbox view does not include it, so `File.isFile` returns false for a
   file plainly visible over adb — the failure gives no hint of the cause.

Both were observed on a Pixel 9a running Android 17 before switching approach: the
tests reported `chosen=null` while `adb shell ls` showed the file sitting there.

So the `stageTestModel` Gradle task copies whatever `scripts/fetch-model.sh`
downloaded into the androidTest assets, and the test extracts it to `cacheDir` at
startup — llama.cpp mmaps a real file, and an asset is a compressed zip entry. With
no model on disk the task copies nothing and every model-dependent test skips itself,
so a CI run without weights stays green and still proves the JNI layer links and
loads.

## D18 — the Application class and the root composable must not share a name

`TaraCoreApplication`, not `TaraCoreApp`, and `TaraCoreRoot()`, not `TaraCoreApp()`.

A class and a function may share a name in Kotlin. When they do, `TaraCoreApp()` in a
composable body resolves to the **constructor**: it silently builds and discards an
`Application` object, emits no UI, and produces no error anywhere. The app launched to
a blank screen, with a clean build, no exception, no crash log, and a composition tree
containing six nodes and no text.

Renamed apart so the collision cannot recur. Worth knowing about generally: it is one
of the few ways to get a Compose screen that renders nothing without any diagnostic.

## D19 — the Playground sends a system prompt, and warns about toy models

Two changes prompted by the first real conversation on device, which drifted badly
and read as a broken app.

The engine was not at fault, and this was checked rather than assumed: the same
messages with the same seed produce byte-identical output whether the prompt is fully
re-decoded (13 of 13 tokens) or served almost entirely from the KV cache (1 token),
and the cache correctly invalidates when a different conversation is interleaved. The
prefix-reuse path in D6 is sound.

What was at fault was everything around it:

- **No system prompt.** An instruct model handed an open question with no framing and
  a 512-token budget fills the budget. The Playground now sends a short system turn
  telling the assistant to answer directly and stop.
- **Sampling defaults tuned for nothing in particular.** Temperature 0.8 and 512
  tokens became 0.7 and 320. A chat reply needing more than 320 tokens is rare; a
  budget the model feels obliged to fill is not.
- **No signal that the loaded model was a smoke test.** SmolLM2-135M is in the catalog
  so that CI has something to load, and its own catalog entry says "too small to be
  useful". Nothing conveyed that in the UI, so the reasonable conclusion from its
  output was that Tara Core was broken. The Playground now shows a warning for any
  resident model under 300 MB and points at the Models tab.

The last point generalises: a shared engine will be judged on the output of whatever
model happens to be loaded, so the UI has to be honest about which of those two is
responsible.

## D20 — the grammar sampler is kept out of the prompt-accepting chain

Constrained decoding (issue #1) crashed the `:engine` process the first time it ran
on a device, and the reason is worth recording because the shape of the mistake
recurs.

`llama_sampler_accept` is called for every prompt token, so that repeat penalties see
the whole context. Putting the grammar sampler in that same chain means the grammar is
handed the prompt — which it cannot match, because a grammar describes the *answer*.
`llama_grammar_accept_str` then throws `std::runtime_error("Unexpected empty grammar
stack")`, nothing caught it, and `std::terminate` aborted the process.

llama.cpp's own `common_sampler_accept` has an explicit `accept_grammar` flag for
exactly this; there is no equivalent on the plain chain API. So the grammar is now a
separate `llama_sampler`, applied to the candidate array before the chain
(`llama_sampler_apply`) and accepting only tokens the model actually produced.

Two consequences worth keeping:

- The grammar **must** be applied before top-k/top-p. Behind them it would only see
  candidates those samplers had already kept, and could be left with nothing legal.
- **A shared service must never let a client's request abort the process.** One
  malformed grammar took down inference for every app on the device. The JNI boundary
  and the generation loop now both catch, turning any llama.cpp exception into a
  failed request. Covered by an instrumented test that asserts the engine still works
  after an invalid grammar.

## D21 — `Gbnf` lives in `:api`, not `:engine`

Clients build grammars to put in `GenerationRequest.grammar`, so the builder has to be
on a module they already depend on. Putting it in `:engine` and having `:client-sdk`
depend on that would drag `libtaracore_jni.so` — several megabytes per ABI — into
every consuming app, which is precisely what Tara Core exists to avoid. `Gbnf` is pure
string building with no dependencies, so `:api` keeps its "depends on nothing"
property.

JSON Schema parsing stays in `:service` for the same reason: it needs a JSON library
and `:api` must not.

## D22 — `allow_auto_load` defaults to the global setting, and unknown models are 404

Three states, not two: `true` swaps, `false` fails fast, **absent** defers to the
global *Load models on demand* setting so no existing caller changes behaviour.

`false` with a non-resident model returns `409`, not `404`: the distinction matters
because a client seeing `409` knows retrying with `true` would work, at the cost of a
load. A model that does not exist is still `404` even when auto-load is off — checked
before the auto-load decision, because reporting "not loaded" for a typo would send
the client off to wait for a load that was never going to happen.

Omitting `model` entirely means "whatever is resident" and never triggers a swap.
That is the setting a background caller actually wants, and it removes the race in
reading `/health` and echoing back what it reported.

## D23 — the orphaned Preferences file is deleted, not migrated

The move to `MultiProcessDataStoreFactory` (D10) left
`filesDir/datastore/taracore_settings.preferences_pb` behind, still holding an HTTP
bearer token.

Deleted rather than migrated, on purpose. The token must be regenerated rather than
resurrected — carrying a superseded credential forward is the opposite of the point.
The remaining values had already diverged from the live ones on every device
inspected, because for a while the two stores were written by different processes.
And the file is an active trap: it is the first thing anyone inspecting the app finds,
its token returns `401`, and the obvious conclusion is that auth is broken.

If a future build ever needs values carried across a store change, migrate *then*
delete; there was nothing here worth keeping.

## D24 — `warmUp()` is a request the service may refuse, not an exposed model id

Issue #5 offered three shapes for pre-warming: expose `activeModelId`, add an
`active` flag to `ModelInfo`, or add a `warmUp()` the service can decline. The
reporter argued for the third because it "gives the service a place to say no", and
that is right — a client cannot reason about whether loading a gigabyte right now is
a good idea for the device as a whole.

Both `warmUp()` and `ServiceStatus.activeModelId` landed: the call for the capability,
the field so a client can *show* what is being warmed. `ModelInfo.active` was left
out — a third place to learn the same fact, with a third chance to disagree.

`warmUp` takes no model id, deliberately. The engine is shared, and a client naming a
model would be choosing for every other app on the device.

The refusals are the substance, so they live in `WarmUpPolicy` as a pure function
rather than inside the Stub method. Free memory moves between runs, which makes
"decline because it will not fit" the one branch a device test cannot pin down; as a
function it is eight unit tests.

Note the asymmetry with `loadModel`: an explicit user choice loads even when the
estimate says it will be tight, because they asked and the Models screen already
warned. A speculative warm-up on behalf of a backgrounded app defers instead. Warming
also does **not** touch the idle timer — warming is not use, and resetting the
countdown would keep a model resident for an app that never went on to ask anything.

## D25 — `Gbnf.SchemaNode.Obj.required` is nullable, and null is not the empty set

Found while adding the SDK's schema builder (issue #4). The original code did
`required.ifEmpty { allKeys }`, conflating "the schema did not say" with "the caller
explicitly wants everything optional" — so a builder marking every field optional
would have produced a grammar making them all mandatory.

`required` is now `Set<String>?`: null means unspecified and is still treated as *all
required* (a fixed shape is far easier for a small model than deciding which optional
keys to include), while an empty set means exactly what it says. `GrammarFactory`
passes null when the incoming JSON Schema has no `required` key.

## D26 — foreground only while working, and the notification follows from that

Users asked not to have a permanent notification. Android does not offer that as an
option: a foreground service without a visible notification is not a thing the
platform allows, and no flag, channel setting or importance level changes it.

So the notification was not hidden — the *foreground state* was made conditional.
`foregroundIsJustified()` returns true for exactly three reasons: the HTTP server is
listening (nothing else keeps the process alive), work is in flight (a client that
asked for a completion and went to the background must still get its answer), or the
user opted into a live status notification. Idle with a model resident is
deliberately not on the list: bound clients keep the service alive by themselves, and
with none, being killed is the correct outcome.

The app also stopped calling `startForegroundService` at launch. Binding alone
creates the service and keeps it alive, and a *bound* service needs no notification
at all. The service is only started when it must outlive the UI process.

Two bugs surfaced on device, both the same shape: `syncForegroundState()` is the
single decision point, and two paths were still calling `updateNotification()`
instead. The engine-state callback left the notification on screen after every
generation, and the settings collector meant toggling live status did nothing at all.
Refreshing a notification is not the same as deciding whether there should be one.

**What the platform still imposes, honestly:** a foreground-service notification is
forced to at least `IMPORTANCE_LOW`, so the `MIN` channel is promoted and a status
bar icon appears for as long as the work lasts. On a short request that is under a
second. There is no way to do better, and claiming otherwise in the UI would be a
lie.

## D27 — backup is disabled outright rather than filtered

`files/taracore_settings.json` holds the HTTP server's bearer token. Backing it up
would copy a live credential to cloud backup and then onto whatever device is
restored next, to gate a server the user may not have enabled there. Models are
gigabytes and must not be backed up either.

What remains worth preserving is a handful of preferences that take seconds to set
again — a poor trade against copying a credential around. `allowBackup="false"`, and
the rule files are kept but exclude everything, so re-enabling backup starts from
"nothing" rather than from a default that sweeps up the token.

Android lint caught the first attempt at this: excluding paths that were never
included is a no-op, and the rules would have silently backed up more than intended.

## D28 — the widget is push-fed and holds nothing

`updatePeriodMillis` has a 30-minute floor, which is useless for tokens per second,
and a widget that woke the engine process to poll would cost more battery than it
could justify. The service instead broadcasts a snapshot when its state changes, and
the widget renders whatever it was last told.

The broadcast is package-targeted, which is what makes it deliverable to a manifest
receiver on Android 8+; a genuinely implicit custom broadcast would be dropped. It is
throttled to at most once a second and only when the payload changed, because it is
called from the generation path.

The consequence, which is the right one: with nothing running, the widget shows the
last known state rather than live data. A widget nobody placed costs nothing at all —
the service checks for placed widget ids before doing any work.

## D29 — UI copy is written for users, not for developers

The first pass at the interface explained itself the way this repository's comments
do: what the kernel was doing, why a KV cache grows, that a foreground service is a
platform requirement. That is the right register for a comment and the wrong one for
a phone screen. Most people using this app will not know what a token is and should
not have to.

Rewritten throughout. The rules applied:

- Say what it does for the reader, not how it works. "Frees up memory when you're not
  using it", not "unloading gives the memory back to whatever you are actually using".
- No jargon where a plain word exists: memory not RAM, speed not throughput, key not
  bearer token, processor not CPU backend.
- Numbers only where they mean something. The context chips are Short / Medium /
  Long / Longest, not 2048 / 4096 / 8192 / 16384. Quantisation shows as "Standard
  quality" and "Higher quality".
- Never show an id. `qwen2.5-0.5b-instruct-q4km` is a database key; the screen says
  "Qwen2.5 0.5B Instruct".
- Hide rows that have no meaning yet. With no model loaded the dashboard used to show
  four dashes and a "none"; it now says what to do instead.
- The genuinely technical controls -- processor cores, GPU layers, mmap, mlock -- are
  grouped under **Advanced** with a warning, rather than sitting between "how much it
  remembers" and "check for updates".

The explanations did not disappear, they moved: the reasoning now lives in code
comments and in `docs/`, where the audience for it actually is.

## D30 — the default model is the recommended one, not the smallest

The fallback when no model is chosen used to be `downloaded().first()`, which sorts
by size and therefore picked the smallest thing on disk. A 0.5B model answers badly
enough that a first-time user concludes the *app* is broken rather than that the model
is small, and they are not wrong to: it is the app's fault for choosing it for them.

`ModelEntity.recommended` marks one catalogue entry (Qwen2.5 1.5B Q4_K_M), and
`bestDownloaded()` prefers it, falling back to the *largest* downloaded model rather
than the smallest. The Models screen marks it "recommended" and sorts it above the
rest.

Smaller models stay in the catalogue -- they are genuinely useful for narrow,
constrained tasks, which is the whole argument of issue #1 -- but they are now a
deliberate choice rather than the one made on the user's behalf.

## D31 — JDK selection stays out of the repository

`org.gradle.java.home` is machine-specific, so it is not committed. `docs/SETUP.md`
tells contributors to export `JAVA_HOME` instead. This matters more than it sounds:
the default `java` on many Linux boxes is a JRE, and Gradle's failure for that case
(`does not provide the required capabilities: [JAVA_COMPILER]`) names neither the JDK
nor the fix.

---

## Open `TODO`s

None. `grep -rn "TODO\|FIXME\|XXX" --exclude-dir=third_party` over the source tree
returns no matches. The one knowingly incomplete item in the repository is the
catalog's missing SHA-256 digests, recorded as D13 above.
