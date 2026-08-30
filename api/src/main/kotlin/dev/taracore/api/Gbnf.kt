package dev.taracore.api

/**
 * Builds GBNF grammars for the shapes clients actually ask for.
 *
 * Why this exists: a small model can classify perfectly well but cannot reliably be
 * *told* to answer in a fixed alphabet. Asking a 0.5B for "just the digit" gets you
 * "Category: 2" or the prompt echoed back. A grammar removes the question — the
 * invalid tokens are not sampled at all — and it helps most on the smallest models,
 * which is exactly where the value of an on-device engine is.
 *
 * Everything here emits a rule named `root`, which is what the engine passes to
 * `llama_sampler_init_grammar`.
 *
 * Deliberately in `:api` rather than `:engine`: clients build grammars to put into
 * [GenerationRequest.grammar], and `:api` is the module they already depend on.
 * Putting it in `:engine` would drag `libtaracore_jni.so` into every consuming app,
 * which is precisely what Tara Core exists to avoid. It is pure string building with
 * no dependencies, so it costs `:api` nothing.
 */
object Gbnf {

    /**
     * Exactly one of [choices], verbatim. The cheapest and most useful constraint.
     *
     * Order is preserved but irrelevant to correctness: the grammar is an
     * alternation, so the model picks whichever its own probabilities favour.
     */
    fun choice(choices: List<String>): String {
        require(choices.isNotEmpty()) { "choice grammar needs at least one option" }
        val alternatives = choices.joinToString(" | ") { quote(it) }
        return "root ::= $alternatives\n"
    }

    /** Any well-formed JSON value. Use when you want JSON but have no schema. */
    fun jsonObject(): String = JSON_PRELUDE + "root ::= object\n"

    /**
     * A grammar for the common subset of JSON Schema.
     *
     * Supports `type` (object, array, string, number, integer, boolean, null),
     * `properties`, `required`, `items`, `enum`, and `const`. Anything it does not
     * understand degrades to "any JSON value" for that position rather than throwing,
     * because a slightly loose constraint is far better than a failed request.
     *
     * Properties are emitted in declaration order and every declared property is
     * required unless `required` says otherwise -- models are much more reliable at
     * filling a fixed shape than at deciding which optional keys to include.
     */
    fun jsonSchema(schema: SchemaNode): String {
        val rules = LinkedHashMap<String, String>()
        val root = build(schema, "root", rules)
        val body = StringBuilder()
        body.append(JSON_PRELUDE)
        rules.forEach { (name, definition) -> body.append("$name ::= $definition\n") }
        if (root != "root") body.append("root ::= $root\n")
        return body.toString()
    }

    // ------------------------------------------------------------------ internals

    /**
     * A parsed JSON Schema, kept deliberately dumb: the HTTP layer hands us one of
     * these rather than a JSON tree, so this module stays free of a JSON dependency
     * and can be unit-tested without one.
     */
    sealed interface SchemaNode {
        data class Obj(
            val properties: List<Pair<String, SchemaNode>>,
            val required: Set<String>,
        ) : SchemaNode

        data class Arr(val items: SchemaNode?, val minItems: Int = 0) : SchemaNode

        data class Enum(val values: List<String>) : SchemaNode

        // Named with a `Type` suffix so they cannot shadow kotlin.Int / kotlin.Any
        // inside this scope -- `minItems: Int` above resolves to the wrong thing
        // otherwise, and the error it produces points nowhere useful.
        data object StringType : SchemaNode
        data object NumberType : SchemaNode
        data object IntegerType : SchemaNode
        data object BooleanType : SchemaNode
        data object NullType : SchemaNode

        /** Unknown or absent type: any JSON value. */
        data object AnyType : SchemaNode
    }

    private fun build(
        node: SchemaNode,
        preferredName: String,
        rules: LinkedHashMap<String, String>,
    ): String = when (node) {
        is SchemaNode.StringType -> "string"
        is SchemaNode.NumberType -> "number"
        is SchemaNode.IntegerType -> "integer"
        is SchemaNode.BooleanType -> "boolean"
        is SchemaNode.NullType -> "null"
        is SchemaNode.AnyType -> "value"

        is SchemaNode.Enum -> {
            val name = unique(preferredName, rules)
            rules[name] = node.values.joinToString(" | ") { quote(it) }
            name
        }

        is SchemaNode.Arr -> {
            val itemRule = node.items?.let { build(it, "${preferredName}-item", rules) } ?: "value"
            val name = unique(preferredName, rules)
            rules[name] = if (node.minItems > 0) {
                // At least one element: emit the first explicitly, then repeat.
                """"[" ws $itemRule (ws "," ws $itemRule)* ws "]""""
            } else {
                """"[" ws ($itemRule (ws "," ws $itemRule)*)? ws "]""""
            }
            name
        }

        is SchemaNode.Obj -> {
            if (node.properties.isEmpty()) {
                "object"
            } else {
                // Child rules must be created before we claim our own name, so that
                // nested rules read in dependency order in the emitted grammar.
                val parts = node.properties.map { (key, child) ->
                    key to build(child, "$preferredName-${sanitise(key)}", rules)
                }
                val name = unique(preferredName, rules)
                val required = node.required.ifEmpty { node.properties.map { it.first }.toSet() }

                // Required keys are emitted in a fixed order; optional ones are each
                // wrapped so they may be skipped. Fixing the order is what makes a
                // small model reliable here -- it never has to decide what comes next.
                val body = StringBuilder("\"{\" ws")
                var first = true
                for ((key, rule) in parts) {
                    val piece = buildString {
                        if (!first) append(" \",\" ws")
                        append(" ${quote("\"$key\"")} ws \":\" ws $rule ws")
                    }
                    if (key in required) {
                        body.append(piece)
                        first = false
                    } else {
                        body.append(" ($piece)?")
                    }
                }
                body.append(" \"}\"")
                rules[name] = body.toString()
                name
            }
        }
    }

    private fun unique(preferred: String, rules: Map<String, String>): String {
        if (preferred !in rules) return preferred
        var i = 2
        while ("$preferred$i" in rules) i++
        return "$preferred$i"
    }

    private fun sanitise(key: String): String =
        key.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-')
            .ifEmpty { "key" }

    /**
     * A GBNF double-quoted literal.
     *
     * Only `\` and `"` need escaping inside one, plus the control characters, which
     * are written as escapes because a raw newline would end the rule.
     */
    private fun quote(literal: String): String {
        val out = StringBuilder("\"")
        for (c in literal) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c.code < 0x20) out.append("\\x%02X".format(c.code)) else out.append(c)
            }
        }
        return out.append('"').toString()
    }

    /**
     * Shared JSON primitives. `ws` allows only spaces and newlines rather than
     * unbounded whitespace: a model that can emit arbitrary indentation will, and
     * every indentation token is one it did not spend on the answer.
     */
    private val JSON_PRELUDE = """
        value   ::= object | array | string | number | boolean | null
        object  ::= "{" ws ( string ws ":" ws value (ws "," ws string ws ":" ws value)* )? ws "}"
        array   ::= "[" ws ( value (ws "," ws value)* )? ws "]"
        string  ::= "\"" char* "\""
        char    ::= [^"\\] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4})
        number  ::= integer ("." [0-9]+)? ([eE] [-+]? [0-9]+)?
        integer ::= "-"? ("0" | [1-9] [0-9]*)
        boolean ::= "true" | "false"
        null    ::= "null"
        ws      ::= [ \n]{0,2}
    """.trimIndent() + "\n"
}
