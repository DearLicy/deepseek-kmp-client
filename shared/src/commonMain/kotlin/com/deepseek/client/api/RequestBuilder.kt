package com.deepseek.client.api

import com.deepseek.client.attachments.Attachment
import com.deepseek.client.domain.*
import com.deepseek.client.protocol.*
import kotlinx.serialization.json.*

/** Mirrors the demo's complete-round context and signed read-link retention. */
object RequestBuilder {
    internal fun contextRounds(history: List<Message>): List<Pair<Message, Message>> = history.zipWithNext().filter { (user, answer) ->
        user.role == MessageRole.User && answer.role == MessageRole.Assistant &&
            answer.status in setOf(MessageStatus.Completed, MessageStatus.Cancelled) && answer.stopReason != "refusal" &&
            answer.blocks.any { it is ContentBlock.Text && it.text.isNotBlank() }
    }

    internal fun questionText(message: Message): String = message.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

    private fun userBlocks(text: String, attachments: List<Attachment>): List<UserContentBlock> = buildList {
        for (attachment in attachments) {
            val id = attachment.serverFileId ?: continue
            val source = FileSource(fileId = id)
            add(if (attachment.isImage) UserContentBlock.Image(source) else UserContentBlock.Document(source))
        }
        if (text.isNotBlank()) add(UserContentBlock.Text(text))
    }

    fun build(history: List<Message>, text: String, thinking: Boolean, search: Boolean, attachments: List<Attachment> = emptyList()): CreateMessageRequest {
        val messages = mutableListOf<MessageParam>()
        contextRounds(history).forEach { (user, answer) ->
            val content = answer.blocks.filterIsInstance<ContentBlock.Text>().map { it.text }.filter { it.isNotBlank() }
            val question = userBlocks(questionText(user), user.attachments)
            if (question.isEmpty()) return@forEach
            messages += MessageParam.User(question)
            val blocks = mutableListOf<AssistantContentBlock>()
            val wire = answer.wireBlocks.entries.sortedBy { it.key }.map { it.value }
            val signed = wire.filter { it["type"]?.jsonPrimitive?.content == "web_fetch_tool_result" && it["kind"]?.jsonPrimitive?.content == "read_link" }
                .filter { !it["signature"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() }
            val ids = signed.mapNotNull { it["tool_use_id"]?.jsonPrimitive?.contentOrNull }.toSet()
            // Historical thinking is excluded; preserve text and signed read-link tool pairs.
            wire.forEach { block ->
                when (block["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> block["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { blocks += AssistantContentBlock.Text(it) }
                    "server_tool_use" -> if (block["id"]?.jsonPrimitive?.contentOrNull in ids) {
                        blocks += AssistantContentBlock.ServerToolUse(block["id"]!!.jsonPrimitive.content, block["name"]!!.jsonPrimitive.content, block["input"]!!, "read_link")
                    }
                    "web_fetch_tool_result" -> if (block in signed) {
                        blocks += AssistantContentBlock.WebFetchResult(block["tool_use_id"]!!.jsonPrimitive.content, block["content"]!!, "read_link", block["signature"]!!.jsonPrimitive.content)
                    }
                }
            }
            if (blocks.none { it is AssistantContentBlock.Text }) blocks.addAll(0, content.map { AssistantContentBlock.Text(it) })
            messages += MessageParam.Assistant(blocks)
        }
        val current = userBlocks(text, attachments)
        require(current.isNotEmpty()) { "A user message needs text or an uploaded attachment" }
        messages += MessageParam.User(current)
        return CreateMessageRequest("deepseek-flash", "该助手为DeepSeek，由深度求索公司创造。", messages,
            ThinkingParam(if (thinking) "enabled" else "disabled"), buildList {
                add(ToolParam.ReadUrl()); if (search) add(ToolParam.WebSearch())
            }, title = TitleParam(true))
    }
}
