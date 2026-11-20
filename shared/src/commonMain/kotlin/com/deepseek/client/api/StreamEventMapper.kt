package com.deepseek.client.api

import com.deepseek.client.domain.*
import com.deepseek.client.mvi.ChatAction
import com.deepseek.client.search.SourceRegistry
import kotlinx.serialization.json.*

/** One mapper per turn. Raw blocks retain signed tool results for subsequent context. */
class StreamEventMapper {
    private val wire = mutableMapOf<Int, JsonObject>()
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun key(turn: TurnId, index: Int) = "${turn.value}-$index"
    private fun source(obj: JsonObject): Source? {
        val url = obj.text("url")?.takeIf(SourceRegistry::isWebUrl) ?: return null
        return Source(url, obj.text("title") ?: url, obj.text("site_name") ?: SourceRegistry.siteNameFromUrl(url),
            obj.text("page_age"), obj.text("snippet"), obj.text("site_icon")?.takeIf(SourceRegistry::isWebUrl), 1)
    }

    fun map(payload: JsonObject, turnId: TurnId): ChatAction? = actions(payload, turnId).firstOrNull()

    fun actions(payload: JsonObject, turn: TurnId): List<ChatAction> {
        val index = (payload["index"] as? JsonPrimitive)?.intOrNull
        return when (payload.text("type")) {
            "content_block_start" -> {
                if (index == null || index < 0 || wire.containsKey(index)) return emptyList()
                val block = payload["content_block"] as? JsonObject ?: return emptyList()
                wire[index] = block
                val id = BlockId(key(turn, index))
                val visible = when (block.text("type")) {
                    "text" -> ContentBlock.Text(id, index, block.text("text").orEmpty())
                    "thinking" -> ContentBlock.Thinking(id, index, block.text("thinking").orEmpty(), block.text("signature"))
                    "server_tool_use" -> {
                        val input = block["input"] as? JsonObject ?: JsonObject(emptyMap())
                        val queries = ((input["queries"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty() +
                            listOfNotNull(input.text("query"), input.text("url"))).distinct()
                        val toolId = block.text("id") ?: id.value
                        if (block.text("kind") == "read_link") ContentBlock.ReadLink(id, index, queries, ToolStatus.Wip, toolId)
                        else ContentBlock.Search(id, index, block.text("kind").orEmpty(), queries, ToolStatus.Wip, emptyList(), toolId)
                    }
                    else -> null
                }
                buildList {
                    if (visible != null) add(ChatAction.BlockStarted(turn, index, visible, id.value))
                    if (block.text("type") == "web_search_tool_result") {
                        val values = (block["content"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
                        add(ChatAction.ToolResult(turn, block.text("tool_use_id").orEmpty(), values.mapNotNull(::source), values.any { it.text("type") == "web_search_tool_result_error" }))
                    }
                    if (block.text("type") == "web_fetch_tool_result") {
                        val content = block["content"] as? JsonObject
                        val title = (content?.get("content") as? JsonObject)?.text("title")
                        val sources = if (content?.text("type") == "web_fetch_result") source(buildJsonObject {
                            put("url", content.text("url")); put("title", title); put("page_age", content.text("published_at"))
                        })?.let(::listOf).orEmpty() else emptyList()
                        add(ChatAction.ToolResult(turn, block.text("tool_use_id").orEmpty(), sources, content?.text("type") != "web_fetch_result"))
                    }
                    add(ChatAction.WireBlock(turn, index, block))
                }
            }
            "content_block_delta" -> {
                if (index == null || index < 0) return emptyList()
                val delta = payload["delta"] as? JsonObject ?: return emptyList()
                val current = wire[index] ?: return emptyList()
                val action: ChatAction = when (delta.text("type")) {
                    "text_delta" -> ChatAction.TextDelta(turn, index, key(turn, index), delta.text("text").orEmpty())
                    "thinking_delta" -> ChatAction.ThinkingDelta(turn, index, key(turn, index), delta.text("thinking").orEmpty())
                    "signature_delta" -> ChatAction.ThinkingDelta(turn, index, key(turn, index), "", delta.text("signature"))
                    "citations_delta" -> {
                        val citation = delta["citation"] as? JsonObject ?: return emptyList()
                        val url = citation.text("url")?.takeIf(SourceRegistry::isWebUrl) ?: return emptyList()
                        ChatAction.CitationDelta(turn, index, key(turn, index), listOf(TextCitation(url, citation.text("title"), citation.text("cited_text").orEmpty())), source(citation)?.let(::listOf).orEmpty())
                    }
                    else -> return emptyList()
                }
                val updated = buildJsonObject {
                    current.forEach { (k, value) -> put(k, value) }
                    when (action) {
                        is ChatAction.TextDelta -> put("text", current.text("text").orEmpty() + action.delta)
                        is ChatAction.ThinkingDelta -> {
                            put("thinking", current.text("thinking").orEmpty() + action.delta)
                            action.signature?.let { put("signature", current.text("signature").orEmpty() + it) }
                        }
                        else -> Unit
                    }
                }
                wire[index] = updated
                listOf(action, ChatAction.WireBlock(turn, index, updated))
            }
            "content_block_stop" -> index?.let { listOf(ChatAction.BlockStopped(turn, it)) }.orEmpty()
            "message_delta" -> {
                val delta = payload["delta"] as? JsonObject ?: return emptyList()
                listOf(ChatAction.ReplyMetadata(turn, delta.text("title"), delta.text("stop_reason") ?: "end_turn"))
            }
            "message_stop" -> listOf(ChatAction.TurnCompleted(turn, null, "end_turn"))
            else -> emptyList()
        }
    }
}
