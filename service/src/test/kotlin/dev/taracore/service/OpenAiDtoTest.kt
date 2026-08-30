package dev.taracore.service

import dev.taracore.service.http.ChatCompletionRequest
import dev.taracore.service.http.CompletionRequest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The acceptance bar for the HTTP surface is that the *unmodified* OpenAI clients
 * work, and those clients send fields we do not implement and shapes that vary. These
 * tests pin the parsing quirks that matter.
 */
class OpenAiDtoTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `accepts stop as a bare string`() {
        val r = json.decodeFromString<ChatCompletionRequest>(
            """{"model":"m","messages":[{"role":"user","content":"hi"}],"stop":"\n\n"}"""
        )
        assertEquals(listOf("\n\n"), r.stopStrings())
    }

    @Test
    fun `accepts stop as an array`() {
        val r = json.decodeFromString<ChatCompletionRequest>(
            """{"model":"m","messages":[],"stop":["END","###"]}"""
        )
        assertEquals(listOf("END", "###"), r.stopStrings())
    }

    @Test
    fun `treats a missing stop as no stop strings`() {
        val r = json.decodeFromString<ChatCompletionRequest>("""{"model":"m","messages":[]}""")
        assertTrue(r.stopStrings().isEmpty())
    }

    @Test
    fun `ignores fields the engine does not implement`() {
        // Straight from the openai package: fields we have no answer for must not
        // make the request fail to parse.
        val r = json.decodeFromString<ChatCompletionRequest>(
            """
            {"model":"m","messages":[{"role":"user","content":"hi"}],
             "tools":[],"tool_choice":"auto","logprobs":true,"top_logprobs":3,
             "response_format":{"type":"text"},"parallel_tool_calls":true,
             "stream_options":{"include_usage":true}}
            """.trimIndent()
        )
        assertEquals("m", r.model)
        assertEquals(1, r.messages.size)
    }

    @Test
    fun `prefers max_completion_tokens over max_tokens`() {
        // Newer clients send both during the transition; the newer field wins.
        val r = json.decodeFromString<ChatCompletionRequest>(
            """{"model":"m","messages":[],"max_tokens":16,"max_completion_tokens":64}"""
        )
        assertEquals(64, r.effectiveMaxTokens())
    }

    @Test
    fun `falls back to a sane default when no token limit is given`() {
        val r = json.decodeFromString<ChatCompletionRequest>("""{"model":"m","messages":[]}""")
        assertEquals(512, r.effectiveMaxTokens())
    }

    @Test
    fun `tolerates a null assistant content`() {
        // Tool-calling exchanges carry assistant turns with content: null.
        val r = json.decodeFromString<ChatCompletionRequest>(
            """{"model":"m","messages":[{"role":"assistant","content":null}]}"""
        )
        assertEquals(null, r.messages.single().content)
    }

    @Test
    fun `joins an array prompt for the completions endpoint`() {
        val r = json.decodeFromString<CompletionRequest>(
            """{"model":"m","prompt":["line one","line two"]}"""
        )
        assertEquals("line one\nline two", r.promptText())
    }

    @Test
    fun `accepts a string prompt`() {
        val r = json.decodeFromString<CompletionRequest>("""{"model":"m","prompt":"once"}""")
        assertEquals("once", r.promptText())
    }
}
