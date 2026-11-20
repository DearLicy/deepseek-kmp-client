package com.deepseek.client.api

import com.deepseek.client.attachments.Attachment
import com.deepseek.client.domain.*
import com.deepseek.client.protocol.CreateMessageRequest
import kotlinx.serialization.json.*
import kotlin.test.*

class AttachmentRequestTest {
    private val image = Attachment("attachment-image", "photo.jpg", 12, "image/jpeg", "file-api-image")
    private val document = Attachment("attachment-doc", "report.pdf", 12, "application/pdf", "file-api-doc")

    @Test fun current_attachments_precede_text_and_use_file_ids() {
        val wire = Json.encodeToString(CreateMessageRequest.serializer(),
            RequestBuilder.build(emptyList(), "请分析", false, false, listOf(image, document)))
        val content = Json.parseToJsonElement(wire).jsonObject["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals(listOf("image", "document", "text"), content.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("file-api-image", content[0].jsonObject["source"]!!.jsonObject["file_id"]!!.jsonPrimitive.content)
        assertEquals("请分析", content[2].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun historical_attachment_context_is_rebuilt_and_missing_remote_ids_are_skipped() {
        val user = Message(MessageId("u"), MessageRole.User, 1, MessageStatus.Completed, attachments = listOf(image, document.copy(serverFileId = null)))
        val answer = Message(MessageId("a"), MessageRole.Assistant, 2, MessageStatus.Completed,
            listOf(ContentBlock.Text(BlockId("t"), 0, "回答")))
        val wire = RequestBuilder.build(listOf(user, answer), "继续", false, false)
        val content = wire.messages.first().let { (it as com.deepseek.client.protocol.MessageParam.User).content }
        assertEquals(1, content.size)
        assertTrue(content[0] is com.deepseek.client.protocol.UserContentBlock.Image)
    }
}
