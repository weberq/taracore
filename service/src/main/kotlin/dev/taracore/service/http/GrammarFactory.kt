package dev.taracore.service.http

import dev.taracore.api.Gbnf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns a request's `response_format` into a GBNF grammar.
 *
 * Lives in `:service` rather than `:api` so that `:api` stays dependency-free:
 * [Gbnf] takes a plain [Gbnf.SchemaNode] tree, and translating JSON Schema into one
 * -- the only part that needs a JSON library -- is this file's whole job.
 */
object GrammarFactory {

    class InvalidResponseFormat(message: String) : IllegalArgumentException(message)

    /**
     * @return the grammar, or null when the request asks for no constraint.
     * @throws InvalidResponseFormat when the client asked for something malformed --
     *         better a 400 naming the problem than an unconstrained answer that
     *         looks like the model ignored the schema.
     */
    fun from(format: ResponseFormat?, rawGrammar: String?): String? {
        // Raw GBNF wins: a client that supplies one has a constraint the structured
        // forms cannot express, and silently overriding it would be worse than useless.
        if (!rawGrammar.isNullOrBlank()) return rawGrammar
        if (format == null) return null

        return when (format.type.lowercase()) {
            "", "text" -> null

            "choice" -> {
                val choices = format.choices
                    ?: throw InvalidResponseFormat(
                        "response_format.type is \"choice\" but no \"choices\" array was given"
                    )
                if (choices.isEmpty()) {
                    throw InvalidResponseFormat("response_format.choices must not be empty")
                }
                Gbnf.choice(choices)
            }

            "json_object" -> Gbnf.jsonObject()

            "json_schema" -> {
                // OpenAI nests it as response_format.json_schema.schema; accept the
                // flat form too, because plenty of clients send that instead.
                val schema = format.schema
                    ?: (format.jsonSchema as? JsonObject)?.get("schema")
                    ?: format.jsonSchema
                    ?: throw InvalidResponseFormat(
                        "response_format.type is \"json_schema\" but no schema was given"
                    )
                Gbnf.jsonSchema(parse(schema))
            }

            else -> throw InvalidResponseFormat(
                "unsupported response_format.type \"${format.type}\"; " +
                    "expected one of: text, choice, json_object, json_schema"
            )
        }
    }

    /**
     * JSON Schema to [Gbnf.SchemaNode], for the subset that matters in practice.
     *
     * Unknown keywords are ignored rather than rejected. A schema this does not fully
     * understand still yields a usable constraint for the parts it does, and a
     * slightly loose grammar beats a failed request.
     */
    private fun parse(element: JsonElement): Gbnf.SchemaNode {
        val obj = element as? JsonObject ?: return Gbnf.SchemaNode.AnyType

        // const and enum pin the value regardless of any declared type.
        (obj["const"] as? JsonPrimitive)?.let {
            return Gbnf.SchemaNode.Enum(listOf(literal(it)))
        }
        (obj["enum"] as? JsonArray)?.let { values ->
            val literals = values.mapNotNull { (it as? JsonPrimitive)?.let(::literal) }
            if (literals.isNotEmpty()) return Gbnf.SchemaNode.Enum(literals)
        }

        // `type` may be an array (["string","null"]); take the first we understand.
        val type = when (val t = obj["type"]) {
            is JsonPrimitive -> t.content
            is JsonArray -> t.firstOrNull()?.jsonPrimitive?.content
            else -> null
        }

        return when (type) {
            "object" -> {
                val props = (obj["properties"] as? JsonObject)
                    ?.map { (key, value) -> key to parse(value) }
                    .orEmpty()
                val required = (obj["required"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?.toSet()
                    .orEmpty()
                Gbnf.SchemaNode.Obj(properties = props, required = required)
            }

            "array" -> Gbnf.SchemaNode.Arr(
                items = obj["items"]?.let(::parse),
                minItems = (obj["minItems"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            )

            "string" -> Gbnf.SchemaNode.StringType
            "number" -> Gbnf.SchemaNode.NumberType
            "integer" -> Gbnf.SchemaNode.IntegerType
            "boolean" -> Gbnf.SchemaNode.BooleanType
            "null" -> Gbnf.SchemaNode.NullType

            // No `type`, but `properties` present: treat it as an object, which is
            // what the author meant even though the schema is technically silent.
            null -> if (obj["properties"] != null) {
                Gbnf.SchemaNode.Obj(
                    properties = (obj["properties"] as JsonObject)
                        .map { (key, value) -> key to parse(value) },
                    required = (obj["required"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet().orEmpty(),
                )
            } else {
                Gbnf.SchemaNode.AnyType
            }

            else -> Gbnf.SchemaNode.AnyType
        }
    }

    /** A JSON primitive as the literal text the model must emit. */
    private fun literal(p: JsonPrimitive): String =
        if (p.isString) "\"${p.content}\"" else p.content
}
