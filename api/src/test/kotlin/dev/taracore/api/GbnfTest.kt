package dev.taracore.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GbnfTest {

    /**
     * The `root` rule only. The shared JSON prelude has its own `(...)?` groups in
     * the generic `object` and `array` rules, so searching the whole grammar for one
     * says nothing about the schema under test.
     */
    private fun rootRule(grammar: String): String =
        grammar.lineSequence().first { it.trimStart().startsWith("root ::=") }

    @Test
    fun `choice emits a single alternation rule`() {
        assertEquals("""root ::= "yes" | "no"""", Gbnf.choice(listOf("yes", "no")).trim())
    }

    @Test
    fun `an object with no required set treats every key as required`() {
        // A schema that does not say which keys are required gets all of them: a
        // fixed shape is far easier for a small model than deciding what to include.
        val g = Gbnf.jsonSchema(
            Gbnf.SchemaNode.Obj(
                properties = listOf(
                    "hour" to Gbnf.SchemaNode.IntegerType,
                    "minute" to Gbnf.SchemaNode.IntegerType,
                ),
                required = null,
            )
        )
        assertFalse("expected both keys mandatory, got:\n$g", rootRule(g).contains(")?"))
        assertTrue(g, g.contains("\\\"hour\\\""))
        assertTrue(g, g.contains("\\\"minute\\\""))
    }

    @Test
    fun `an explicitly empty required set makes every key optional`() {
        // Regression: an empty set used to be conflated with "unspecified", which
        // silently made every field mandatory -- the opposite of what was asked.
        val g = Gbnf.jsonSchema(
            Gbnf.SchemaNode.Obj(
                properties = listOf(
                    "hour" to Gbnf.SchemaNode.IntegerType,
                    "label" to Gbnf.SchemaNode.StringType,
                ),
                required = emptySet(),
            )
        )
        assertTrue("expected optional groups, got:\n$g", rootRule(g).contains(")?"))
    }

    @Test
    fun `a partial required set marks only those keys mandatory`() {
        val g = Gbnf.jsonSchema(
            Gbnf.SchemaNode.Obj(
                properties = listOf(
                    "hour" to Gbnf.SchemaNode.IntegerType,
                    "label" to Gbnf.SchemaNode.StringType,
                ),
                required = setOf("hour"),
            )
        )
        assertTrue("label should be optional, got:\n$g", rootRule(g).contains(")?"))
        assertTrue(g, g.contains("\\\"hour\\\""))
    }

    @Test
    fun `literals with quotes and backslashes are escaped`() {
        val g = Gbnf.choice(listOf("""a"b""", """c\d"""))
        assertTrue(g, g.contains("""a\"b"""))
        assertTrue(g, g.contains("""c\\d"""))
    }

    @Test
    fun `newlines in a literal do not break the rule`() {
        val g = Gbnf.choice(listOf("a\nb"))
        assertFalse("a raw newline would end the rule early:\n$g", g.trim().contains("\n"))
        assertTrue(g, g.contains("\\n"))
    }

    @Test
    fun `nested objects and arrays produce distinct rules`() {
        val g = Gbnf.jsonSchema(
            Gbnf.SchemaNode.Obj(
                properties = listOf(
                    "items" to Gbnf.SchemaNode.Arr(Gbnf.SchemaNode.StringType, minItems = 1),
                    "meta" to Gbnf.SchemaNode.Obj(
                        properties = listOf("id" to Gbnf.SchemaNode.IntegerType),
                        required = setOf("id"),
                    ),
                ),
                required = setOf("items", "meta"),
            )
        )
        assertTrue(g, g.contains("root"))
        assertTrue(g, g.contains("\\\"items\\\""))
        assertTrue(g, g.contains("\\\"meta\\\""))
        assertTrue(g, g.contains("\\\"id\\\""))
    }

    @Test
    fun `an empty choice set is rejected`() {
        val e = runCatching { Gbnf.choice(emptyList()) }.exceptionOrNull()
        assertTrue("expected an IllegalArgumentException, got $e", e is IllegalArgumentException)
    }
}
