package com.deepseek.client.bridge

import com.deepseek.client.attachments.*
import com.deepseek.client.domain.*
import com.deepseek.client.mvi.*
import com.deepseek.client.runtime.ChatController
import com.deepseek.client.storage.*
import com.deepseek.client.transport.KtorTransport
import com.deepseek.client.transport.HttpTransport
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Synchronous platform storage, invoked on Dispatchers.Default, never the UI thread. */
interface NativeStorage {
    fun readKey(): NativeRead
    fun writeKey(value: String): Boolean
    fun clearKey(): Boolean
    fun readSnapshot(): NativeRead
    fun writeSnapshot(value: String): Boolean
}
class NativeRead(val value: String?, val failed: Boolean)

/** Synchronous platform file bridge, called off the UI thread by NativeClient. */
interface NativeAttachmentStorage {
    fun read(id: String, limit: Int): NativeAttachmentBytes
    fun write(id: String, bytes: ByteArray): Boolean
    fun delete(ids: List<String>): Boolean
    fun prepareImage(name: String, mediaType: String, bytes: ByteArray): NativePreparedImage?
}
class NativeAttachmentBytes(val value: ByteArray?, val failed: Boolean)
class NativePreparedImage(val name: String, val mediaType: String, val bytes: ByteArray)

/** Stable projection across the ObjC boundary; Swift never reduces business state. */
@Serializable
data class NativeViewState(
    val ready: Boolean, val configured: Boolean, val keyMasked: String?, val keyChecking: Boolean, val keyError: String?,
    val storageError: String?, val draft: String, val thinking: Boolean, val search: Boolean, val theme: String, val fontScale: Float,
    val selectedId: String?, val activeId: String?, val notice: String?, val sessions: List<NativeSession>,
    val attachments: List<NativeAttachment>, val preparingAttachments: Boolean,
    val drafts: Map<String, String>, val draftAttachments: Map<String, List<NativeAttachment>>,
)
@Serializable
data class NativeSession(val id: String, val title: String, val pinned: Boolean, val updatedAt: Long, val messages: List<NativeMessage>)
@Serializable
data class NativeMessage(val id: String, val role: String, val status: String, val error: String?, val blocks: List<NativeBlock>,
    val sources: List<Source>, val createdAt: Long, val attachments: List<NativeAttachment>)
@Serializable
data class NativeAttachment(val id: String, val name: String, val mediaType: String, val sizeBytes: Int)
@Serializable
data class NativeBlock(val id: String, val kind: String, val text: String, val collapsed: Boolean = false,
    val startedAt: Long? = null, val endedAt: Long? = null, val status: String? = null, val citations: List<TextCitation> = emptyList())

object NativeProjection {
    private val json = Json { encodeDefaults = true }
    fun encode(state: AppState): String = json.encodeToString(NativeViewState.serializer(), NativeViewState(
        state.ready, state.apiKeyConfigured, state.apiKeyMasked, state.keyChecking, state.keyError, state.storageError,
        state.draft, state.thinkingEnabled, state.searchEnabled, state.preferences.theme, state.preferences.fontScale,
        state.selectedSessionId?.value, state.activeTurnId?.value, state.transient.message,
        state.sessions.map { session -> NativeSession(session.id.value, session.title, session.pinned, session.updatedAt, session.messages.map { message ->
            NativeMessage(message.id.value, message.role.name, message.status.name, message.error, message.blocks.map { block -> when (block) {
                is ContentBlock.Text -> NativeBlock(block.id.value, "text", block.text, citations = block.citations)
                is ContentBlock.Thinking -> NativeBlock(block.id.value, "thinking", block.text, block.id in state.collapsedThinkingBlocks, block.startedAt, block.endedAt)
                is ContentBlock.Search -> NativeBlock(block.id.value, "search", block.queries.joinToString("、"), status = block.status.name)
                is ContentBlock.ReadLink -> NativeBlock(block.id.value, "readLink", block.links.joinToString("、"), status = block.status.name)
            } }, message.sources, message.createdAt, message.attachments.map(::file))
        }) },
        state.attachments.map(::file), state.preparingAttachments && state.selectedIsGenerating,
        state.drafts, state.draftAttachments.mapValues { (_, values) -> values.map(::file) },
    ))

    private fun file(value: Attachment) = NativeAttachment(value.id, value.name, value.mediaType, value.sizeBytes)
}

class NativeClient(
    storage: NativeStorage, transport: HttpTransport,
    attachments: AttachmentStorage?, images: ImagePreprocessor?,
) {
    constructor(storage: NativeStorage) : this(storage, KtorTransport(), null, null)
    constructor(storage: NativeStorage, transport: HttpTransport) : this(storage, transport, null, null)
    constructor(storage: NativeStorage, files: NativeAttachmentStorage) : this(storage, KtorTransport(),
        object : AttachmentStorage {
            override suspend fun read(id: String, limit: Int) = withContext(Dispatchers.Default) {
                files.read(id, limit).let { check(!it.failed); it.value }
            }
            override suspend fun write(id: String, bytes: ByteArray) = withContext(Dispatchers.Default) {
                check(files.write(id, bytes))
            }
            override suspend fun delete(ids: Set<String>) = withContext(Dispatchers.Default) {
                check(files.delete(ids.toList()))
            }
        },
        ImagePreprocessor { file ->
            withContext(Dispatchers.Default) {
                val result = files.prepareImage(file.name, file.mediaType, file.bytes)
                    ?: throw AttachmentFailure("无法处理这张图片")
                UploadFile(result.name, result.mediaType, result.bytes)
            }
        },
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller = ChatController(scope, object : SecureKeyStore {
        override suspend fun read() = withContext(Dispatchers.Default) { storage.readKey().let { check(!it.failed); it.value } }
        override suspend fun save(value: String) { withContext(Dispatchers.Default) { check(storage.writeKey(value)) } }
        override suspend fun clear() { withContext(Dispatchers.Default) { check(storage.clearKey()) } }
    }, object : SnapshotStore {
        override suspend fun read() = withContext(Dispatchers.Default) { storage.readSnapshot().let { check(!it.failed); it.value } }
        override suspend fun write(value: String) { withContext(Dispatchers.Default) { check(storage.writeSnapshot(value)) } }
    }, transport, attachmentStorage = attachments, imagePreprocessor = images)
    private var observing: Job? = null
    fun start(onState: (String) -> Unit) {
        observing?.cancel()
        observing = scope.launch { controller.state.collect { onState(NativeProjection.encode(it)) } }
        scope.launch { controller.initialize() }
    }
    fun input(text: String) { controller.submit(ChatIntent.InputChanged(text)) }
    fun send() { controller.send() }
    fun stop() { controller.stop() }
    fun newSession() { controller.submit(ChatIntent.NewSession) }
    fun selectSession(id: String) { controller.submit(ChatIntent.SelectSession(SessionId(id))) }
    fun renameSession(id: String, title: String) { controller.submit(ChatIntent.RenameSession(SessionId(id), title)) }
    fun pinSession(id: String) { controller.submit(ChatIntent.PinSession(SessionId(id))) }
    fun deleteSession(id: String) { controller.submit(ChatIntent.DeleteSession(SessionId(id))) }
    fun deleteAll() { controller.submit(ChatIntent.DeleteAll) }
    fun toggleThinking() { controller.submit(ChatIntent.ToggleThinking) }
    fun toggleSearch() { controller.submit(ChatIntent.ToggleSearch) }
    fun toggleThinkingBlock(id: String) { controller.submit(ChatIntent.ToggleThinkingBlock(BlockId(id))) }
    fun setTheme(value: String) { controller.submit(ChatIntent.SetTheme(value)) }
    fun setFontScale(value: Float) { controller.submit(ChatIntent.SetFontScale(value)) }
    fun saveKey(value: String) { controller.validateAndSaveKey(value) }
    fun clearKey() { controller.clearKey() }
    fun dismissNotice() { controller.submit(ChatIntent.DismissMessage) }
    fun showNotice(message: String) { controller.submit(ChatIntent.ShowMessage(message)) }
    fun removeAttachment(id: String) { controller.submit(ChatIntent.RemoveAttachment(id)) }
    fun editForResend(id: String) { controller.submit(ChatIntent.EditForResend(MessageId(id))) }
    internal suspend fun stageAttachments(inputs: List<AttachmentInput>) = controller.stageAttachments(inputs)
    /** Swift/Kotlin bridge for one imported file; the platform has already read its bounded bytes. */
    fun stageAttachment(name: String, bytes: ByteArray) {
        scope.launch { controller.stageAttachments(listOf(AttachmentInput(name) { bytes })) }
    }
    fun flush() { scope.launch { controller.flush() } }
    fun close() { observing?.cancel(); controller.close(); scope.cancel() }
}
