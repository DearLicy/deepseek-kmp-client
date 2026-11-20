package com.deepseek.client.domain

import com.deepseek.client.attachments.Attachment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SessionId(val value: String)
@Serializable
data class MessageId(val value: String)
@Serializable
data class BlockId(val value: String)
@Serializable
data class TurnId(val value: String)
@Serializable
data class DraftId(val value: String)
@Serializable
data class RecordingId(val value: String)

@Serializable
enum class MessageRole { User, Assistant }

@Serializable
enum class MessageStatus { Draft, Streaming, Completed, Failed, Cancelled }

@Serializable
data class Session(
    val id: SessionId,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val manuallyTitled: Boolean = false,
    val messages: List<Message> = emptyList(),
)

@Serializable
data class Message(
    val id: MessageId,
    val role: MessageRole,
    val createdAt: Long,
    val status: MessageStatus,
    val blocks: List<ContentBlock> = emptyList(),
    val sources: List<Source> = emptyList(),
    val error: String? = null,
    val stopReason: String? = null,
    val wireBlocks: Map<Int, JsonObject> = emptyMap(),
    val attachments: List<Attachment> = emptyList(),
)

@Serializable
sealed interface ContentBlock {
    val id: BlockId
    val index: Int

    @Serializable
    data class Text(
        override val id: BlockId,
        override val index: Int,
        val text: String,
        val citations: List<TextCitation> = emptyList(),
    ) : ContentBlock

    @Serializable
    data class Thinking(
        override val id: BlockId,
        override val index: Int,
        val text: String,
        val signature: String? = null,
        val startedAt: Long? = null,
        val endedAt: Long? = null,
    ) : ContentBlock

    @Serializable
    data class Search(
        override val id: BlockId,
        override val index: Int,
        val kind: String,
        val queries: List<String>,
        val status: ToolStatus,
        val results: List<Source>,
        val toolUseId: String = id.value,
    ) : ContentBlock

    @Serializable
    data class ReadLink(
        override val id: BlockId,
        override val index: Int,
        val links: List<String>,
        val status: ToolStatus,
        val toolUseId: String = id.value,
    ) : ContentBlock
}

@Serializable
enum class ToolStatus { Wip, Done, Failed }

@Serializable
data class TextCitation(
    val url: String,
    val title: String?,
    val citedText: String,
)

@Serializable
data class Source(
    val url: String,
    val title: String,
    val siteName: String,
    val publishedAt: String?,
    val snippet: String?,
    val siteIcon: String?,
    val index: Int,
)

@Serializable
data class ReplyView(
    val steps: List<ContentBlock>,
    val answerMarkdown: String?,
    val sources: List<Source>,
    val searchResults: List<Source>,
)
