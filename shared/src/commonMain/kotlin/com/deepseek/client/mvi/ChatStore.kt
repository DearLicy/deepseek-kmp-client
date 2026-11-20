package com.deepseek.client.mvi

import com.deepseek.client.domain.*
import com.deepseek.client.attachments.Attachment
import com.deepseek.client.attachments.AttachmentPolicy
import com.deepseek.client.platform.platformNowMillis
import com.deepseek.client.search.SourceRegistry
import com.deepseek.client.voice.PublicVoiceCatalog
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.atomics.AtomicLong

@Serializable
data class Preferences(
    val thinking: Boolean = false,
    val search: Boolean = false,
    val theme: String = "system",
    val fontScale: Float = 1f,
    val voiceId: String = PublicVoiceCatalog.defaultVoiceId,
)

data class AppState(
    val ready: Boolean = false,
    val apiKeyConfigured: Boolean = false,
    val apiKeyMasked: String? = null,
    val keyChecking: Boolean = false,
    val keyError: String? = null,
    val storageError: String? = null,
    val sessions: List<Session> = emptyList(),
    val selectedSessionId: SessionId? = null,
    val activeTurnId: TurnId? = null,
    val activeSessionId: SessionId? = null,
    val activeMessageId: MessageId? = null,
    val draft: String = "",
    val drafts: Map<String, String> = emptyMap(),
    val draftAttachments: Map<String, List<Attachment>> = emptyMap(),
    val preparingAttachments: Boolean = false,
    val preferences: Preferences = Preferences(),
    val collapsedThinkingBlocks: Set<BlockId> = emptySet(),
    val transient: TransientState = TransientState(),
) {
    val thinkingEnabled get() = preferences.thinking
    val searchEnabled get() = preferences.search
    val ttsVoiceId get() = preferences.voiceId
    val selectedSession get() = sessions.firstOrNull { it.id == selectedSessionId }
    val selectedIsGenerating get() = activeTurnId != null && activeSessionId == selectedSessionId
    val attachments get() = draftAttachments[selectedSessionId?.value ?: "new"].orEmpty()
}

data class TransientState(val message: String? = null)

sealed interface ChatIntent {
    data class Restore(
        val sessions: List<Session>, val drafts: Map<String, String>, val preferences: Preferences,
        val draftAttachments: Map<String, List<Attachment>> = emptyMap(),
    ) : ChatIntent
    data class Ready(val storageError: String? = null) : ChatIntent
    data class ApiKeyState(val configured: Boolean, val masked: String?) : ChatIntent
    data class KeyCheck(val checking: Boolean, val error: String? = null) : ChatIntent
    data class StorageError(val message: String?) : ChatIntent
    data class InputChanged(val text: String) : ChatIntent
    data class AttachmentStaged(val sessionId: SessionId?, val attachment: Attachment) : ChatIntent
    data class RemoveAttachment(val id: String) : ChatIntent
    data class EditForResend(val replyId: MessageId) : ChatIntent
    data object ToggleThinking : ChatIntent
    data object ToggleSearch : ChatIntent
    data class SetTheme(val value: String) : ChatIntent
    data class SetFontScale(val value: Float) : ChatIntent
    data object NewSession : ChatIntent
    data class SelectSession(val id: SessionId) : ChatIntent
    data class RenameSession(val id: SessionId, val title: String) : ChatIntent
    data class PinSession(val id: SessionId) : ChatIntent
    data class DeleteSession(val id: SessionId) : ChatIntent
    data object DeleteAll : ChatIntent
    data class ToggleThinkingBlock(val id: BlockId) : ChatIntent
    data class SendMessage(val turnId: TurnId, val text: String) : ChatIntent
    data class CancelTurn(val turnId: TurnId) : ChatIntent
    data class ShowMessage(val message: String) : ChatIntent
    data object DismissMessage : ChatIntent
    data class TtsVoiceSelected(val voiceId: String) : ChatIntent
}

sealed interface ChatAction {
    val turnId: TurnId
    data class AttachmentUpdated(override val turnId: TurnId, val value: Attachment) : ChatAction
    data class AttachmentsPrepared(override val turnId: TurnId, val skipped: Int) : ChatAction
    data class TurnStarted(
        override val turnId: TurnId,
        val sessionId: SessionId,
        val message: Message,
        val startedAt: Long,
    ) : ChatAction

    data class BlockStarted(
        override val turnId: TurnId,
        val index: Int,
        val block: ContentBlock,
        val blockKey: String,
    ) : ChatAction

    data class TextDelta(
        override val turnId: TurnId,
        val index: Int,
        val blockKey: String,
        val delta: String,
    ) : ChatAction

    data class ThinkingDelta(
        override val turnId: TurnId,
        val index: Int,
        val blockKey: String,
        val delta: String,
        val signature: String? = null,
        val ended: Boolean = false,
        val endedAt: Long? = null,
    ) : ChatAction

    data class CitationDelta(
        override val turnId: TurnId,
        val index: Int,
        val blockKey: String,
        val citations: List<TextCitation>,
        val sources: List<Source>,
    ) : ChatAction

    data class BlockStopped(override val turnId: TurnId, val index: Int) : ChatAction
    data class WireBlock(override val turnId: TurnId, val index: Int, val value: JsonObject) : ChatAction
    data class ToolResult(override val turnId: TurnId, val toolUseId: String, val sources: List<Source>, val failed: Boolean) : ChatAction
    data class SourcesAdded(
        override val turnId: TurnId,
        val sources: List<Source>,
    ) : ChatAction

    data class ToolStatusChanged(
        override val turnId: TurnId,
        val index: Int,
        val blockKey: String,
        val status: ToolStatus,
    ) : ChatAction

    data class ReplyMetadata(
        override val turnId: TurnId,
        val title: String?,
        val stopReason: String,
    ) : ChatAction

    data class TurnCompleted(
        override val turnId: TurnId,
        val title: String?,
        val stopReason: String,
    ) : ChatAction

    data class TurnFailed(
        override val turnId: TurnId,
        val message: String,
        val cancelled: Boolean = false,
    ) : ChatAction
}

sealed interface ChatEffect {
    data class SendMessage(
        val turnId: TurnId, val sessionId: SessionId, val userId: MessageId,
        val thinking: Boolean, val search: Boolean,
    ) : ChatEffect
    data class CancelTurn(val turnId: TurnId) : ChatEffect
}

data class Transition(val state: AppState, val effects: List<ChatEffect> = emptyList())

/** Pure state transition: all time and generated identifiers arrive from the caller. */
object ChatReducer {
    private fun draftKey(id: SessionId?) = id?.value ?: "new"
    private fun AppState.withDraftSaved() = copy(drafts = drafts + (draftKey(selectedSessionId) to draft))

    fun reduce(state: AppState, intent: ChatIntent, now: Long): Transition {
        fun result(next: AppState) = Transition(next)
        fun stop(next: AppState): Transition {
            val turn = state.activeTurnId ?: return result(next)
            return Transition(finish(next, state, MessageStatus.Cancelled, now), listOf(ChatEffect.CancelTurn(turn)))
        }
        return when (intent) {
            is ChatIntent.Restore -> result(state.copy(sessions = intent.sessions, drafts = intent.drafts,
                draft = intent.drafts["new"].orEmpty(), preferences = intent.preferences, draftAttachments = intent.draftAttachments))
            is ChatIntent.Ready -> result(state.copy(ready = true, storageError = intent.storageError))
            is ChatIntent.ApiKeyState -> if (intent.configured) result(state.copy(apiKeyConfigured = true, apiKeyMasked = intent.masked))
                else stop(state.copy(apiKeyConfigured = false, apiKeyMasked = null, keyChecking = false, keyError = null))
            is ChatIntent.KeyCheck -> result(state.copy(keyChecking = intent.checking, keyError = intent.error))
            is ChatIntent.StorageError -> result(state.copy(storageError = intent.message))
            is ChatIntent.InputChanged -> result(state.copy(draft = intent.text).withDraftSaved())
            is ChatIntent.AttachmentStaged -> {
                if (intent.sessionId != null && state.sessions.none { it.id == intent.sessionId }) return result(state)
                val key = draftKey(intent.sessionId)
                val files = state.draftAttachments[key].orEmpty()
                if (files.size >= AttachmentPolicy.MAX_PER_DRAFT || files.any { it.id == intent.attachment.id }) return result(state)
                result(state.copy(draftAttachments = state.draftAttachments + (key to (files + intent.attachment))))
            }
            is ChatIntent.RemoveAttachment -> {
                val key = draftKey(state.selectedSessionId)
                val files = state.attachments.filterNot { it.id == intent.id }
                result(state.copy(draftAttachments = if (files.isEmpty()) state.draftAttachments - key else state.draftAttachments + (key to files)))
            }
            is ChatIntent.EditForResend -> {
                if (state.activeTurnId != null) return result(state)
                val messages = state.selectedSession?.messages.orEmpty()
                val index = messages.indexOfFirst { it.id == intent.replyId && it.role == MessageRole.Assistant }
                val question = messages.getOrNull(index - 1)?.takeIf { it.role == MessageRole.User } ?: return result(state)
                // Retrying a failed turn restores both body and source files.
                val key = draftKey(state.selectedSessionId)
                val text = question.blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
                result(state.copy(draft = text, draftAttachments = state.draftAttachments + (key to question.attachments)).withDraftSaved())
            }
            ChatIntent.ToggleThinking -> result(state.copy(preferences = state.preferences.copy(thinking = !state.thinkingEnabled)))
            ChatIntent.ToggleSearch -> result(state.copy(preferences = state.preferences.copy(search = !state.searchEnabled)))
            is ChatIntent.SetTheme -> result(if (intent.value in listOf("system", "light", "dark")) state.copy(preferences = state.preferences.copy(theme = intent.value)) else state)
            is ChatIntent.SetFontScale -> result(state.copy(preferences = state.preferences.copy(fontScale = intent.value.coerceIn(0.8f, 1.4f))))
            ChatIntent.NewSession -> result(state.withDraftSaved().let { it.copy(selectedSessionId = null, draft = it.drafts["new"].orEmpty()) })
            is ChatIntent.SelectSession -> result(if (state.sessions.none { it.id == intent.id }) state else state.withDraftSaved().let { it.copy(selectedSessionId = intent.id, draft = it.drafts[intent.id.value].orEmpty()) })
            is ChatIntent.RenameSession -> result(state.copy(sessions = state.sessions.map { if (it.id == intent.id) it.copy(title = intent.title.trim().take(120).ifEmpty { "新对话" }, manuallyTitled = true) else it }))
            is ChatIntent.PinSession -> result(state.copy(sessions = state.sessions.map { if (it.id == intent.id) it.copy(pinned = !it.pinned) else it }))
            is ChatIntent.DeleteSession -> {
                val next = state.copy(sessions = state.sessions.filterNot { it.id == intent.id }, drafts = state.drafts - intent.id.value,
                    draftAttachments = state.draftAttachments - intent.id.value,
                    selectedSessionId = if (state.selectedSessionId == intent.id) null else state.selectedSessionId,
                    draft = if (state.selectedSessionId == intent.id) state.drafts["new"].orEmpty() else state.draft)
                if (state.activeSessionId == intent.id) stop(next) else result(next)
            }
            ChatIntent.DeleteAll -> stop(state.copy(sessions = emptyList(), drafts = emptyMap(), draftAttachments = emptyMap(),
                selectedSessionId = null, draft = "", collapsedThinkingBlocks = emptySet()))
            is ChatIntent.ToggleThinkingBlock -> result(state.copy(collapsedThinkingBlocks = if (intent.id in state.collapsedThinkingBlocks) state.collapsedThinkingBlocks - intent.id else state.collapsedThinkingBlocks + intent.id))
            is ChatIntent.SendMessage -> {
                if (!state.apiKeyConfigured) return result(state.copy(transient = TransientState("请先配置 API Key")))
                val text = intent.text.trim()
                val files = state.attachments
                if (state.keyChecking || state.activeTurnId != null || (text.isEmpty() && files.isEmpty())) return result(state)
                val id = state.selectedSessionId ?: SessionId("session-${intent.turnId.value}")
                val title = text.lineSequence().first().ifBlank { files.first().name }.take(24)
                val existing = state.sessions.firstOrNull { it.id == id } ?: Session(id, title, now, now)
                val userId = MessageId("${intent.turnId.value}-user")
                val user = Message(userId, MessageRole.User, now, MessageStatus.Completed,
                    if (text.isEmpty()) emptyList() else listOf(ContentBlock.Text(BlockId("${intent.turnId.value}-question"), 0, text)),
                    attachments = files)
                val messageId = MessageId("${intent.turnId.value}-assistant")
                val assistant = Message(messageId, MessageRole.Assistant, now, MessageStatus.Streaming)
                val updated = existing.copy(updatedAt = now, messages = existing.messages + user + assistant)
                val hasAttachments = files.isNotEmpty() || existing.messages.any { it.attachments.isNotEmpty() }
                Transition(state.copy(sessions = listOf(updated) + state.sessions.filterNot { it.id == id }, selectedSessionId = id,
                    activeTurnId = intent.turnId, activeSessionId = id, activeMessageId = messageId, draft = "",
                    preparingAttachments = hasAttachments, draftAttachments = state.draftAttachments - draftKey(state.selectedSessionId),
                    drafts = state.drafts - draftKey(state.selectedSessionId) - id.value),
                    listOf(ChatEffect.SendMessage(intent.turnId, id, userId, state.thinkingEnabled, state.searchEnabled)))
            }
            is ChatIntent.CancelTurn -> if (state.activeTurnId == intent.turnId) stop(state) else result(state)
            is ChatIntent.ShowMessage -> result(state.copy(transient = TransientState(intent.message)))
            ChatIntent.DismissMessage -> result(state.copy(transient = TransientState()))
            is ChatIntent.TtsVoiceSelected -> result(if (PublicVoiceCatalog.voices.any { it.id == intent.voiceId }) state.copy(preferences = state.preferences.copy(voiceId = intent.voiceId)) else state)
        }
    }

    fun reduce(state: AppState, action: ChatAction, now: Long): Transition {
        if (state.activeTurnId != action.turnId) return Transition(state)
        fun update(transform: (Message) -> Message): Transition = Transition(state.copy(sessions = state.sessions.map { session ->
            if (session.id != state.activeSessionId) session else session.copy(messages = session.messages.map { if (it.id == state.activeMessageId) transform(it) else it })
        }))
        fun replace(message: Message, index: Int, block: ContentBlock): Message = message.copy(blocks = (message.blocks.filterNot { it.index == index } + block).sortedBy { it.index })
        fun sources(values: List<Source>): List<Source> = SourceRegistry().also { registry -> values.forEach { registry.add(it.url, it.title, it.publishedAt, it.snippet, it.siteIcon, it.siteName) } }.all
        return when (action) {
            is ChatAction.AttachmentUpdated -> {
                fun replaceFile(files: List<Attachment>) = files.map { if (it.id == action.value.id) action.value else it }
                Transition(state.copy(
                    sessions = state.sessions.map { session -> session.copy(messages = session.messages.map { message ->
                        message.copy(attachments = replaceFile(message.attachments))
                    }) },
                    draftAttachments = state.draftAttachments.mapValues { replaceFile(it.value) },
                    preparingAttachments = true,
                ))
            }
            is ChatAction.AttachmentsPrepared -> Transition(state.copy(preparingAttachments = false, transient =
                if (action.skipped == 0) state.transient else TransientState("有 ${action.skipped} 个历史附件未能加入本次对话")))
            is ChatAction.TurnStarted -> Transition(state) // Send already created the stable assistant placeholder.
            is ChatAction.BlockStarted -> update { msg ->
                if (msg.blocks.any { it.index == action.index }) msg else replace(msg, action.index,
                    if (action.block is ContentBlock.Thinking) action.block.copy(startedAt = now) else action.block)
            }
            is ChatAction.TextDelta -> update { msg ->
                val block = msg.blocks.find { it.index == action.index } as? ContentBlock.Text
                if (block == null) msg else replace(msg, action.index, block.copy(text = block.text + action.delta))
            }
            is ChatAction.ThinkingDelta -> update { msg ->
                val block = msg.blocks.find { it.index == action.index } as? ContentBlock.Thinking
                if (block == null) msg else replace(msg, action.index, block.copy(text = block.text + action.delta, signature = action.signature?.let { block.signature.orEmpty() + it } ?: block.signature,
                    endedAt = action.endedAt ?: if (action.ended) now else block.endedAt))
            }
            is ChatAction.BlockStopped -> update { msg -> msg.copy(blocks = msg.blocks.map { if (it is ContentBlock.Thinking && it.index == action.index) it.copy(endedAt = now) else it }) }
            is ChatAction.WireBlock -> update { it.copy(wireBlocks = it.wireBlocks + (action.index to action.value)) }
            is ChatAction.CitationDelta -> update { msg ->
                val block = msg.blocks.find { it.index == action.index } as? ContentBlock.Text
                val updated = if (block == null) msg else replace(msg, action.index, block.copy(citations = (block.citations + action.citations).distinctBy { it.url }))
                updated.copy(sources = sources(updated.sources + action.sources))
            }
            is ChatAction.SourcesAdded -> update { it.copy(sources = sources(it.sources + action.sources)) }
            is ChatAction.ToolResult -> update { msg -> msg.copy(sources = sources(msg.sources + action.sources), blocks = msg.blocks.map { block ->
                val status = if (action.failed) ToolStatus.Failed else ToolStatus.Done
                when {
                    block is ContentBlock.Search && block.toolUseId == action.toolUseId -> block.copy(status = status, results = action.sources)
                    block is ContentBlock.ReadLink && block.toolUseId == action.toolUseId -> block.copy(status = status)
                    else -> block
                }
            }) }
            is ChatAction.ToolStatusChanged -> update { msg -> msg.copy(blocks = msg.blocks.map { block -> when {
                block is ContentBlock.Search && block.index == action.index -> block.copy(status = action.status)
                block is ContentBlock.ReadLink && block.index == action.index -> block.copy(status = action.status)
                else -> block
            } }) }
            is ChatAction.ReplyMetadata -> Transition(state.copy(sessions = state.sessions.map { session ->
                if (session.id != state.activeSessionId) session else session.copy(title = if (!session.manuallyTitled) action.title?.takeIf { it.isNotBlank() } ?: session.title else session.title,
                    messages = session.messages.map { if (it.id == state.activeMessageId) it.copy(stopReason = action.stopReason) else it })
            }))
            is ChatAction.TurnCompleted -> {
                val titled = if (action.title != null) reduce(state, ChatAction.ReplyMetadata(action.turnId, action.title, action.stopReason), now).state else state
                Transition(finish(titled, state, MessageStatus.Completed, now))
            }
            is ChatAction.TurnFailed -> Transition(finish(state, state, if (action.cancelled) MessageStatus.Cancelled else MessageStatus.Failed, now, action.message))
        }
    }

    private fun finish(next: AppState, active: AppState, status: MessageStatus, now: Long, error: String? = null): AppState = next.copy(
        activeTurnId = null, activeSessionId = null, activeMessageId = null,
        preparingAttachments = false,
        sessions = next.sessions.map { session -> if (session.id != active.activeSessionId) session else session.copy(updatedAt = now, messages = session.messages.map { msg ->
            if (msg.id != active.activeMessageId) msg else msg.copy(status = status, error = error, blocks = msg.blocks.map { block -> when (block) {
                is ContentBlock.Thinking -> block.copy(endedAt = block.endedAt ?: now)
                is ContentBlock.Search -> if (block.status == ToolStatus.Wip) block.copy(status = ToolStatus.Failed) else block
                is ContentBlock.ReadLink -> if (block.status == ToolStatus.Wip) block.copy(status = ToolStatus.Failed) else block
                else -> block
            } })
        }) },
    )
}

/** Serialized facade; platform UI only submits intents and observes state. */
@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
class ChatStore(initialState: AppState = AppState(), private val nowMillis: () -> Long = ::platformNowMillis) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(initialState)
    private val ids = AtomicLong(0)
    val state: StateFlow<AppState> = mutable.asStateFlow()
    fun newId(prefix: String): String = "$prefix-${nowMillis()}-${ids.addAndFetch(1)}"
    suspend fun dispatch(intent: ChatIntent): List<ChatEffect> = mutex.withLock {
        ChatReducer.reduce(mutable.value, intent, nowMillis()).let { mutable.value = it.state; it.effects }
    }
    suspend fun dispatch(action: ChatAction): List<ChatEffect> = mutex.withLock {
        ChatReducer.reduce(mutable.value, action, nowMillis()).let { mutable.value = it.state; it.effects }
    }
    internal fun isLate(turn: TurnId): Boolean = state.value.activeTurnId != turn
}
