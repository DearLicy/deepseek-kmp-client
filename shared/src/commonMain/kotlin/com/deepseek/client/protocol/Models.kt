package com.deepseek.client.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

enum class ChatModel(val wireValue: String) {
    Flash("deepseek-flash"),
    V4Pro("deepseek-v4-pro"),
}

@Serializable
data class CreateMessageRequest(
    val model: String,
    val system: String,
    val messages: List<MessageParam>,
    val thinking: ThinkingParam,
    val tools: List<ToolParam> = emptyList(),
    @SerialName("safety_check") val safetyCheck: SafetyCheckParam = SafetyCheckParam(),
    val title: TitleParam? = null,
    val stream: Boolean = true,
)

@Serializable
data class ThinkingParam(val type: String)

@Serializable
data class SafetyCheckParam(val level: String = "high")

@Serializable
data class TitleParam(val enabled: Boolean)

@Serializable
sealed interface ToolParam {
    @Serializable
    @SerialName("deepseek_web_search")
    data class WebSearch(val name: String = "deepseek_web_search") : ToolParam

    @Serializable
    @SerialName("deepseek_read_url")
    data class ReadUrl(val name: String = "deepseek_read_url") : ToolParam
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("role")
sealed interface MessageParam {
    @Serializable
    @SerialName("user")
    data class User(val content: List<UserContentBlock>) : MessageParam

    @Serializable
    @SerialName("assistant")
    data class Assistant(val content: List<AssistantContentBlock>) : MessageParam
}

@Serializable
sealed interface UserContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : UserContentBlock

    @Serializable
    @SerialName("image")
    data class Image(val source: FileSource) : UserContentBlock

    @Serializable
    @SerialName("document")
    data class Document(val source: FileSource) : UserContentBlock
}

@Serializable
data class FileSource(
    val type: String = "file",
    @SerialName("file_id") val fileId: String,
)

@Serializable
sealed interface AssistantContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : AssistantContentBlock

    @Serializable
    @SerialName("thinking")
    data class Thinking(val thinking: String, val signature: String) : AssistantContentBlock

    @Serializable
    @SerialName("server_tool_use")
    data class ServerToolUse(val id: String, val name: String, val input: JsonElement, val kind: String) : AssistantContentBlock

    @Serializable
    @SerialName("web_fetch_tool_result")
    data class WebFetchResult(@SerialName("tool_use_id") val toolUseId: String, val content: JsonElement, val kind: String, val signature: String? = null) : AssistantContentBlock

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonElement,
        val kind: String? = null,
    ) : AssistantContentBlock
}

@Serializable
data class TextCitation(
    val type: String,
    val url: String,
    val title: String?,
    @SerialName("cited_text") val citedText: String,
    @SerialName("encrypted_index") val encryptedIndex: String = "",
)

@Serializable
data class MessageUsage(
    @SerialName("input_tokens") val inputTokens: Int,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int,
    @SerialName("output_tokens") val outputTokens: Int,
)

@Serializable
data class MessageStart(
    val id: String,
    val model: String,
    val usage: MessageUsage? = null,
)

@Serializable
data class MessageDelta(
    @SerialName("stop_reason") val stopReason: String,
    @SerialName("stop_sequence") val stopSequence: String? = null,
    val title: String? = null,
)

@Serializable
data class WebSearchResultWire(
    val type: String,
    val title: String,
    val url: String,
    @SerialName("image_url") val imageUrl: String? = null,
    val snippet: String,
    @SerialName("site_icon") val siteIcon: String? = null,
    @SerialName("site_name") val siteName: String? = null,
    @SerialName("page_age") val pageAge: String? = null,
)

@Serializable
data class WebSearchToolResultWire(
    val type: String,
    @SerialName("tool_use_id") val toolUseId: String,
    val content: List<JsonElement>,
    val kind: String,
)

@Serializable
data class WebFetchContentWire(
    val type: String,
    val title: String? = null,
)

@Serializable
data class WebFetchResultWire(
    val type: String,
    val url: String,
    val content: WebFetchContentWire,
    @SerialName("published_at") val publishedAt: String? = null,
)

@Serializable
data class WebFetchToolResultWire(
    val type: String,
    @SerialName("tool_use_id") val toolUseId: String,
    val content: JsonElement,
    val kind: String,
    val signature: String? = null,
)

@Serializable
data class ServerToolUseWire(
    val type: String,
    val id: String,
    val name: String,
    val input: JsonElement,
    val kind: String? = null,
)
