package com.deepseek.client.runtime

import com.deepseek.client.attachments.*
import com.deepseek.client.domain.MessageStatus
import com.deepseek.client.domain.TurnId
import com.deepseek.client.mvi.ChatIntent
import com.deepseek.client.storage.*
import com.deepseek.client.transport.KtorTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AttachmentNetworkTest {
    private val original = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1)
    private val resized = original.copyOf().apply { this[3] = 2 }
    private val beta = "files-api-2025-04-14"

    @Test fun old_image_is_reuploaded_from_the_original_and_new_id_is_reused() = runBlocking {
        val server = MockWebServer().apply { start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val storage = MemoryFiles()
        val history = MemoryHistory()
        fun controller() = ChatController(scope, TestKey(), history, KtorTransport(),
            server.url("/").toString().trimEnd('/'), storage, ImagePreprocessor { it.copy(bytes = resized) })
        val first = controller()
        try {
            first.initialize()
            first.stageAttachments(listOf(AttachmentInput("photo.jpg") { original }))
            val localId = first.state.value.attachments.single().id
            server.enqueue(upload("file-api-original"))
            server.enqueue(reply())
            first.sendAndWait("first", "")
            assertEquals("/anthropic/v1/files", server.takeRequest(2, TimeUnit.SECONDS)?.path)
            assertTrue(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8().contains("file-api-original"))
            first.flush()
            first.close()
            val old = SnapshotCodec.decode(checkNotNull(history.value))
            val session = old.sessions.single()
            history.value = SnapshotCodec.encode(old.copy(sessions = listOf(session.copy(messages = session.messages.map { message ->
                message.copy(attachments = message.attachments.map { it.copy(imagePolicyVersion = 0) })
            }))))
            val reopened = controller()
            try {
                reopened.initialize()
                reopened.dispatch(ChatIntent.SelectSession(session.id))
                server.enqueue(upload("file-api-resized"))
                server.enqueue(reply())
                reopened.sendAndWait("second", "再看一次")
                val repair = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals(beta, repair.getHeader("anthropic-beta"))
                assertTrue(resized.asList().let { signature -> repair.body.readByteArray().asList().windowed(signature.size).any { it == signature } })
                val repaired = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals(beta, repaired.getHeader("anthropic-beta"))
                assertTrue(repaired.body.readUtf8().contains("file-api-resized"))
                assertContentEquals(original, storage.read(localId, 64))
                server.enqueue(reply())
                reopened.sendAndWait("third", "继续")
                assertEquals("/anthropic/v1/messages", server.takeRequest(2, TimeUnit.SECONDS)?.path)
            } finally { reopened.close() }
        } finally { first.close(); scope.cancel(); server.shutdown() }
    }

    @Test fun invalid_file_id_is_replaced_before_retrying_messages() = runBlocking {
        val server = MockWebServer().apply { start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = ChatController(scope, TestKey(), MemoryHistory(), KtorTransport(),
            server.url("/").toString().trimEnd('/'), MemoryFiles())
        try {
            controller.initialize()
            controller.stageAttachments(listOf(AttachmentInput("report.txt") { "test file".encodeToByteArray() }))
            server.enqueue(upload("file-api-expired"))
            server.enqueue(MockResponse().setResponseCode(400).setBody(
                """{"error":{"message":"file data not found for file_id file-api-expired"}}"""))
            server.enqueue(upload("file-api-new"))
            server.enqueue(reply())
            controller.sendAndWait("first", "读文档")
            val initialUpload = server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()
            val purposeOffset = Regex("name=\"?purpose\"?").find(initialUpload)?.range?.first ?: -1
            val fileOffset = Regex("name=\"?file\"?").find(initialUpload)?.range?.first ?: -1
            assertTrue(purposeOffset in 0 until fileOffset, initialUpload)
            assertTrue(initialUpload.contains("user_data"))
            assertTrue(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8().contains("file-api-expired"))
            assertEquals("/anthropic/v1/files", server.takeRequest(2, TimeUnit.SECONDS)?.path)
            assertTrue(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8().contains("file-api-new"))
        } finally { controller.close(); scope.cancel(); server.shutdown() }
    }

    private suspend fun ChatController.sendAndWait(id: String, text: String) {
        dispatch(ChatIntent.SendMessage(TurnId(id), text))
        val done = withTimeout(8000) { state.first { it.activeTurnId == null && it.selectedSession?.messages?.lastOrNull()?.id?.value == "$id-assistant" } }
        val reply = done.selectedSession!!.messages.last()
        assertEquals(MessageStatus.Completed, reply.status, reply.error)
    }

    private fun upload(id: String) = MockResponse().setBody("""{"id":"$id"}""")
    private fun reply() = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"ok\"}}\n\n" +
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n" +
            "data: {\"type\":\"message_stop\"}\n\n")

    private class TestKey : SecureKeyStore {
        override suspend fun read() = "synthetic-key"
        override suspend fun save(value: String) = Unit
        override suspend fun clear() = Unit
    }
    private class MemoryHistory : SnapshotStore {
        var value: String? = null
        override suspend fun read() = value
        override suspend fun write(value: String) { this.value = value }
    }
    private class MemoryFiles : AttachmentStorage {
        private val files = mutableMapOf<String, ByteArray>()
        override suspend fun read(id: String, limit: Int) = files[id]?.takeIf { it.size <= limit }
        override suspend fun write(id: String, bytes: ByteArray) { files[id] = bytes }
        override suspend fun delete(ids: Set<String>) { files.keys.removeAll(ids) }
    }
}
