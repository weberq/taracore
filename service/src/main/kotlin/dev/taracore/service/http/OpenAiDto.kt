package dev.taracore.service.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The OpenAI wire shapes, as much of them as an on-device engine can honour.
 *
 * These are deliberately permissive on input (`ignoreUnknownKeys`, `stop` accepting
 * either a string or an array) and strict on output, because the acceptance test is
 * that the unmodified `openai` Python package works against this server -- and that
 * package sends fields we do not implement and parses responses strictly.
 */

@Serializable
data class ChatMessageDto(
    val role: String,
    // Nullable because assistant messages in a tool-calling exchange carry no content.
    val content: String? = null,
    val name: String? = null,
)

@Serializable
data class ChatCompletionRequest(
    val model: String? = null,
    val messages: List<ChatMessageDto> = emptyList(),
    @SerialName("max_tokens") val maxTokens: Int? = null,
    /** Newer clients send this instead of max_tokens. */
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("top_k") val topK: Int? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Float? = null,
    @SerialName("presence_penalty") val presencePenalty: Float? = null,
    /** OpenAI allows a string or an array of up to four. */
    val stop: JsonElement? = null,
    val seed: Long? = null,
    val stream: Boolean = false,
    val n: Int? = null,
    val user: String? = null,
) {
    fun stopStrings(): List<String> = stop.toStringList()
    fun effectiveMaxTokens(): Int = maxCompletionTokens ?: maxTokens ?: 512
}

@Serializable
data class CompletionRequest(
    val model: String? = null,
    val prompt: JsonElement? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("top_k") val topK: Int? = null,
    val stop: JsonElement? = null,
    val seed: Long? = null,
    val stream: Boolean = false,
    val n: Int? = null,
) {
    fun stopStrings(): List<String> = stop.toStringList()

    /** `prompt` may be a string or an array of strings; arrays are joined. */
    fun promptText(): String {
        val element = prompt ?: return ""
        if (element is JsonPrimitive) return element.content
        return runCatching {
            element.jsonArray.joinToString("\n") { it.jsonPrimitive.content }
        }.getOrDefault("")
    }
}

private fun JsonElement?.toStringList(): List<String> {
    val element = this ?: return emptyList()
    if (element is JsonPrimitive) return listOf(element.content).filter { it.isNotEmpty() }
    return runCatching {
        element.jsonArray.map { it.jsonPrimitive.content }.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())
}

// ------------------------------------------------------------------ responses

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
)

/** Not part of the OpenAI schema; a Tara Core extension used by scripts/bench.sh. */
@Serializable
data class Timings(
    @SerialName("prompt_ms") val promptMs: Long,
    @SerialName("generation_ms") val generationMs: Long,
    @SerialName("tokens_per_second") val tokensPerSecond: Double,
)

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessageDto,
    @SerialName("finish_reason") val finishReason: String,
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<ChatChoice>,
    val usage: Usage,
    val timings: Timings? = null,
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null,
)

@Serializable
data class ChatChunkChoice(
    val index: Int = 0,
    val delta: Delta,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChatChunkChoice>,
)

@Serializable
data class TextChoice(
    val index: Int = 0,
    val text: String,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class CompletionResponse(
    val id: String,
    @SerialName("object") val objectType: String = "text_completion",
    val created: Long,
    val model: String,
    val choices: List<TextChoice>,
    val usage: Usage,
    val timings: Timings? = null,
)

@Serializable
data class ModelDto(
    val id: String,
    @SerialName("object") val objectType: String = "model",
    val created: Long,
    @SerialName("owned_by") val ownedBy: String = "tara-core",
)

@Serializable
data class ModelListResponse(
    @SerialName("object") val objectType: String = "list",
    val data: List<ModelDto>,
)

@Serializable
data class HealthResponse(
    val name: String = "tara-core",
    val status: String,
    val model: String? = null,
    val backend: String,
    @SerialName("api_version") val apiVersion: Int,
    val engine: String = "",
)

/** OpenAI's error envelope. Clients parse this shape on non-2xx. */
@Serializable
data class ErrorBody(val error: ErrorDetail)

@Serializable
data class ErrorDetail(
    val message: String,
    val type: String,
    val param: String? = null,
    val code: String? = null,
)
