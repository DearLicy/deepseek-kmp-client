package com.deepseek.client.runtime

import com.deepseek.client.api.*
import com.deepseek.client.attachments.*
import com.deepseek.client.domain.*
import com.deepseek.client.mvi.*
import com.deepseek.client.protocol.MessageParam
import com.deepseek.client.protocol.UserContentBlock
import com.deepseek.client.storage.*
import com.deepseek.client.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Owns I/O lifetime; all state changes pass through ChatStore. Never holds a Key in public state. */
class ChatController(
    private val scope: CoroutineScope,
    private val keys: SecureKeyStore,
    private val snapshots: SnapshotStore,
    private val transport: HttpTransport,
    apiOrigin: String = "https://api.deepseek.com",
    private val attachmentStorage: AttachmentStorage? = null,
    imagePreprocessor: ImagePreprocessor? = null,
) {
    val store = ChatStore()
    val state = store.state
    private val api = DeepSeekApi(transport, apiOrigin)
    private val attachments = PrepareAttachments(attachmentStorage, imagePreprocessor, api)
    private val jobs = mutableMapOf<TurnId, Job>()
    private val commands = Mutex()
    private val keyOperations = Mutex()
    private var initialized = false
    private var saving: Job? = null
    private var validation: Job? = null
    private var savingEnabled = true
    private val pendingFileDeletes = mutableSetOf<String>()

    suspend fun initialize() = commands.withLock {
        if (initialized) return@withLock
        initialized = true
        var failure: String? = null
        try {
            snapshots.read()?.let { text ->
                val value = SnapshotCodec.decode(text)
                store.dispatch(ChatIntent.Restore(value.sessions, value.drafts, value.preferences, value.draftAttachments))
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            savingEnabled = false // Preserve unreadable history; don't overwrite it with an empty state.
            failure = "本地记录无法读取，原文件已保留；请先备份后处理"
        }
        try {
            val key = keys.read()
            store.dispatch(ChatIntent.ApiKeyState(!key.isNullOrBlank(), key?.let(::mask)))
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { failure = "安全存储无法读取，请重新配置 API Key" }
        store.dispatch(ChatIntent.Ready(failure))
        saving = scope.launch {
            @OptIn(FlowPreview::class)
            state.map(::snapshotOf)
                .distinctUntilChanged().debounce(350).collect { saveSnapshot() }
        }
    }

    suspend fun dispatch(intent: ChatIntent) = commands.withLock {
        if (!state.value.ready) return@withLock
        // Explicitly confirmed delete-all is also the recovery path for unreadable history.
        if (intent == ChatIntent.DeleteAll) savingEnabled = true
        val removesFiles = intent is ChatIntent.RemoveAttachment || intent is ChatIntent.DeleteSession ||
            intent == ChatIntent.DeleteAll || intent is ChatIntent.EditForResend
        val priorFiles = if (removesFiles) state.value.referencedFiles() else emptySet()
        val effects = store.dispatch(intent)
        if (removesFiles) pendingFileDeletes += priorFiles - state.value.referencedFiles()
        effects.forEach { effect -> when (effect) {
            is ChatEffect.CancelTurn -> jobs.remove(effect.turnId)?.cancel()
            is ChatEffect.SendMessage -> {
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val key = keys.read()?.takeIf { it.isNotBlank() } ?: throw HttpException(HttpException.Kind.Http, "请先配置 API Key")
                        sendTurn(key, effect)
                    } catch (cancelled: CancellationException) {
                        // Reducer cancellation already ended the matching turn; late events are ignored.
                        withContext(NonCancellable) { store.dispatch(ChatAction.TurnFailed(effect.turnId, "已停止", true)) }
                        throw cancelled
                    } catch (error: Exception) {
                        store.dispatch(ChatAction.TurnFailed(effect.turnId, api.readError(error)))
                    } finally {
                        withContext(NonCancellable) { commands.withLock { jobs.remove(effect.turnId) } }
                    }
                }
                jobs[effect.turnId] = job
                job.start()
            }
        } }
    }

    private suspend fun sendTurn(key: String, effect: ChatEffect.SendMessage) {
        val turn = effect.turnId
        var attempts = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val messages = state.value.sessions.firstOrNull { it.id == effect.sessionId }?.messages.orEmpty()
            val index = messages.indexOfFirst { it.id == effect.userId }
            val question = messages.getOrNull(index) ?: return
            val files = attachments.forTurn(key, messages.take(index), question) {
                store.dispatch(ChatAction.AttachmentUpdated(turn, it))
            }
            store.dispatch(ChatAction.AttachmentsPrepared(turn, files.skipped))
            val request = RequestBuilder.build(files.history, RequestBuilder.questionText(question), effect.thinking, effect.search, files.current)
            val mapper = StreamEventMapper()
            var receivedContent = false
            try {
                api.streamMessage(key, request) { event, payload ->
                    receivedContent = receivedContent || event == "content_block_start" || payload["type"]?.toString() == "\"content_block_start\""
                    mapper.actions(payload, turn).forEach { store.dispatch(it) }
                }
                return
            } catch (error: HttpException) {
                val invalid = error.invalidFileIds ?: throw error
                if (receivedContent || attempts >= 2) throw error
                val requested = request.messages.filterIsInstance<MessageParam.User>().flatMap { it.content }.mapNotNull { block ->
                    when (block) {
                        is UserContentBlock.Image -> block.source.fileId
                        is UserContentBlock.Document -> block.source.fileId
                        is UserContentBlock.Text -> null
                    }
                }.toSet()
                val candidates = if (invalid.isEmpty()) requested else requested.intersect(invalid)
                if (candidates.isEmpty()) throw error
                val filesToClear = (files.current + RequestBuilder.contextRounds(files.history).flatMap { it.first.attachments })
                    .filter { it.serverFileId in candidates }.distinctBy { it.id }
                if (filesToClear.isEmpty()) throw error
                filesToClear.forEach { store.dispatch(ChatAction.AttachmentUpdated(turn, it.copy(serverFileId = null, imagePolicyVersion = 0))) }
                delay(1000L shl attempts++)
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun stageAttachments(inputs: List<AttachmentInput>) {
        val destination = state.value.selectedSessionId
        commands.withLock {
            val storage = attachmentStorage ?: return@withLock
            if (!state.value.ready) return@withLock
            val key = destination?.value ?: "new"
            val limit = AttachmentPolicy.MAX_PER_DRAFT - state.value.draftAttachments[key].orEmpty().size
            if (inputs.size > limit) {
                store.dispatch(ChatIntent.ShowMessage("每条消息最多选择 ${AttachmentPolicy.MAX_PER_DRAFT} 个附件"))
                return@withLock
            }
            for (input in inputs) {
                val id = "attachment-${Uuid.random()}"
                try {
                    val name = AttachmentPolicy.normalizeName(input.name)
                    val bytes = input.read(AttachmentPolicy.maxBytes(name))
                    val file = AttachmentPolicy.describe(id, name, bytes)
                    storage.write(file.id, bytes)
                    store.dispatch(ChatIntent.AttachmentStaged(destination, file))
                    if (file.id !in state.value.draftAttachments[key].orEmpty().map { it.id }) pendingFileDeletes += file.id
                } catch (cancelled: CancellationException) {
                    pendingFileDeletes += id
                    throw cancelled
                } catch (error: Exception) {
                    pendingFileDeletes += id
                    store.dispatch(ChatIntent.ShowMessage(if (error is AttachmentFailure) error.message.orEmpty() else "无法读取或保存附件"))
                }
            }
            persistSnapshot()
        }
    }

    suspend fun readAttachment(id: String): UploadFile? = commands.withLock {
        if (!AttachmentPolicy.isLocalId(id)) return@withLock null
        val current = state.value
        val file = current.draftAttachments.values.asSequence().flatten().firstOrNull { it.id == id }
            ?: current.sessions.asSequence().flatMap { it.messages.asSequence() }.flatMap { it.attachments.asSequence() }
                .firstOrNull { it.id == id }
            ?: return@withLock null
        attachmentStorage?.read(id, AttachmentPolicy.maxBytes(file.name))?.let { UploadFile(file.name, file.mediaType, it) }
    }

    fun send() = scope.launch { dispatch(ChatIntent.SendMessage(TurnId(store.newId("turn")), state.value.draft)) }
    fun stop() = scope.launch { state.value.activeTurnId?.let { dispatch(ChatIntent.CancelTurn(it)) } }
    fun submit(intent: ChatIntent) { scope.launch { dispatch(intent) } }

    fun validateAndSaveKey(input: String) {
        if (validation?.isActive == true) return
        validation = scope.launch {
            keyOperations.withLock {
                val key = input.trim()
                if (key.isBlank() || key.encodeToByteArray().size >= 8192) {
                    store.dispatch(ChatIntent.KeyCheck(false, "请输入有效的 API Key")); return@withLock
                }
                val canValidate = commands.withLock {
                    if (state.value.activeTurnId != null) {
                        store.dispatch(ChatIntent.KeyCheck(false, "请先停止当前生成"))
                        false
                    } else {
                        store.dispatch(ChatIntent.KeyCheck(true))
                        true
                    }
                }
                if (!canValidate) return@withLock
                try {
                    api.verifyApiKey(key)
                    keys.save(key) // Only mark configured once persistence succeeds.
                    store.dispatch(ChatIntent.ApiKeyState(true, mask(key)))
                    store.dispatch(ChatIntent.KeyCheck(false))
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) { store.dispatch(ChatIntent.KeyCheck(false)) }
                    throw cancelled
                } catch (error: Exception) {
                    store.dispatch(ChatIntent.KeyCheck(false, if (error is HttpException) api.readError(error) else "无法保存到安全存储"))
                }
            }
        }
    }

    fun clearKey() = scope.launch {
        validation?.cancelAndJoin()
        keyOperations.withLock {
            try {
                dispatch(ChatIntent.ApiKeyState(false, null))
                keys.clear()
            } catch (error: Exception) { store.dispatch(ChatIntent.KeyCheck(false, "无法清除安全存储，请重试")) }
        }
    }

    suspend fun flush() {
        if (state.value.ready) saveSnapshot()
    }

    private suspend fun saveSnapshot() = commands.withLock { persistSnapshot() }

    private suspend fun persistSnapshot() {
        val current = state.value
        if (!savingEnabled) return
        try {
            snapshots.write(SnapshotCodec.encode(snapshotOf(current)))
            val unreferenced = pendingFileDeletes - current.referencedFiles()
            if (unreferenced.isNotEmpty()) attachmentStorage?.delete(unreferenced)
            pendingFileDeletes.removeAll(unreferenced)
            store.dispatch(ChatIntent.StorageError(null))
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { store.dispatch(ChatIntent.StorageError("本地保存失败，记录暂留在内存，请重试")) }
    }

    private fun AppState.referencedFiles(): Set<String> = buildSet {
        draftAttachments.values.forEach { files -> files.forEach { add(it.id) } }
        sessions.forEach { session -> session.messages.forEach { message -> message.attachments.forEach { add(it.id) } } }
    }

    private fun snapshotOf(state: AppState) = LocalSnapshot(sessions = state.sessions, drafts = state.drafts,
        preferences = state.preferences, draftAttachments = state.draftAttachments)

    fun close() { validation?.cancel(); jobs.values.forEach { it.cancel() }; saving?.cancel(); transport.close() }
    private fun mask(key: String) = "•••• " + key.takeLast(4)
}
