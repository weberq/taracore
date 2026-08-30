package dev.taracore.service

import dev.taracore.api.Gbnf
import dev.taracore.service.http.ChatCompletionRequest
import dev.taracore.service.http.GrammarFactory
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grammar is the difference between a 0.5B model being usable for classification
 * and not, so the shapes clients send have to survive the trip intact.
 */
class GrammarFactoryTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(body: String) = json.decodeFromString<ChatCompletionRequest>(body)

    private fun grammarFor(body: String): String? {
        val r = parse(body)
        return GrammarFactory.from(r.responseFormat, r.grammar)
    }

    @Test
    fun `no response_format means no constraint`() {
        assertNull(grammarFor("""{"model":"m","messages":[]}"""))
        assertNull(grammarFor("""{"model":"m","messages":[],"response_format":{"type":"text"}}"""))
    }

    @Test
    fun `choice produces an alternation of the exact options`() {
        val g = grammarFor(
            """{"model":"m","messages":[],
                "response_format":{"type":"choice","choices":["1","2","3","4","5","6"]}}"""
        )!!
        assertEquals("""root ::= "1" | "2" | "3" | "4" | "5" | "6"""", g.trim())
    }

    @Test
    fun `choice escapes quotes and backslashes in options`() {
        // An unescaped quote would end the GBNF literal early and the grammar would
        // either fail to parse or, worse, parse into something else.
        val g = Gbnf.choice(listOf("""say "hi"""", """back\slash"""))
        assertTrue(g, g.contains("""\"hi\""""))
        assertTrue(g, g.contains("""back\\slash"""))
    }

    @Test
    fun `choice rejects an empty set rather than emitting an impossible grammar`() {
        val e = assertThrows(GrammarFactory.InvalidResponseFormat::class.java) {
            grammarFor("""{"model":"m","messages":[],
                           "response_format":{"type":"choice","choices":[]}}""")
        }
        assertTrue(e.message!!, e.message!!.contains("must not be empty"))
    }

    @Test
    fun `choice without a choices array is a client error`() {
        assertThrows(GrammarFactory.InvalidResponseFormat::class.java) {
            grammarFor("""{"model":"m","messages":[],"response_format":{"type":"choice"}}""")
        }
    }

    @Test
    fun `an unknown response_format type is rejected, not ignored`() {
        // Ignoring it would return unconstrained text that looks like the model
        // disobeyed, which is the failure this whole feature exists to remove.
        val e = assertThrows(GrammarFactory.InvalidResponseFormat::class.java) {
            grammarFor("""{"model":"m","messages":[],"response_format":{"type":"yaml"}}""")
        }
        assertTrue(e.message!!, e.message!!.contains("yaml"))
    }

    @Test
    fun `json_object yields a grammar rooted at object`() {
        val g = grammarFor("""{"model":"m","messages":[],
                               "response_format":{"type":"json_object"}}""")!!
        assertTrue(g, g.contains("root ::= object"))
        assertTrue(g, g.contains("string  ::="))
    }

    @Test
    fun `json_schema builds a fixed-order object with the declared keys`() {
        val g = grammarFor(
            """{"model":"m","messages":[],"response_format":{"type":"json_schema","schema":{
                 "type":"object",
                 "properties":{"category":{"type":"string"},"confidence":{"type":"number"}},
                 "required":["category","confidence"]}}}"""
        )!!
        assertTrue(g, g.contains("\\\"category\\\""))
        assertTrue(g, g.contains("\\\"confidence\\\""))
        assertTrue(g, g.contains("root ::=") || g.contains("root "))
    }

    @Test
    fun `json_schema accepts the nested OpenAI shape`() {
        // OpenAI sends response_format.json_schema.schema, not response_format.schema.
        val g = grammarFor(
            """{"model":"m","messages":[],"response_format":{"type":"json_schema",
                 "json_schema":{"name":"cat","schema":{
                   "type":"object","properties":{"c":{"type":"integer"}},"required":["c"]}}}}"""
        )!!
        assertTrue(g, g.contains("\\\"c\\\""))
        assertTrue(g, g.contains("integer"))
    }

    @Test
    fun `an enum in a schema becomes a fixed alternation`() {
        val g = grammarFor(
            """{"model":"m","messages":[],"response_format":{"type":"json_schema","schema":{
                 "type":"object",
                 "properties":{"category":{"enum":["food","travel","rent"]}},
                 "required":["category"]}}}"""
        )!!
        assertTrue(g, g.contains("\\\"food\\\""))
        assertTrue(g, g.contains("\\\"travel\\\""))
        assertTrue(g, g.contains("\\\"rent\\\""))
    }

    @Test
    fun `raw grammar overrides response_format`() {
        val g = grammarFor(
            """{"model":"m","messages":[],"grammar":"root ::= \"yes\" | \"no\"",
                "response_format":{"type":"json_object"}}"""
        )!!
        assertEquals("""root ::= "yes" | "no"""", g)
    }

    @Test
    fun `a schema with no recognised type degrades to any JSON value`() {
        // Loose beats failed: the client still gets valid JSON out.
        val g = grammarFor("""{"model":"m","messages":[],
                               "response_format":{"type":"json_schema","schema":{"foo":"bar"}}}""")!!
        assertTrue(g, g.contains("value"))
    }

    @Test
    fun `allow_auto_load parses as a nullable tri-state`() {
        assertNull(parse("""{"model":"m","messages":[]}""").allowAutoLoad)
        assertEquals(false, parse("""{"model":"m","messages":[],"allow_auto_load":false}""").allowAutoLoad)
        assertEquals(true, parse("""{"model":"m","messages":[],"allow_auto_load":true}""").allowAutoLoad)
    }

    @Test
    fun `model may be omitted entirely`() {
        // "whatever is loaded" -- the request that must never trigger a swap.
        assertNull(parse("""{"messages":[{"role":"user","content":"hi"}]}""").model)
    }
}
