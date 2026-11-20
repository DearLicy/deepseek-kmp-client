package com.deepseek.client.attachments

import kotlin.test.*

class AttachmentPolicyTest {
    @Test fun names_are_sanitized_and_limited() {
        assertEquals("report.pdf", AttachmentPolicy.normalizeName("../../report.PDF"))
        assertEquals("notes.txt", AttachmentPolicy.normalizeName("C:\\Users\\me\\notes.txt"))
        assertFailsWith<AttachmentFailure> { AttachmentPolicy.normalizeName("archive.exe") }
    }

    @Test fun image_signature_and_dimensions_are_checked_before_upload() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        assertEquals("image/png", AttachmentPolicy.describe("attachment-1", "photo.png", png).mediaType)
        assertFailsWith<AttachmentFailure> { AttachmentPolicy.describe("attachment-1", "photo.png", byteArrayOf(1, 2, 3)) }
        assertEquals(ImageSize(4096, 2048), AttachmentPolicy.fitImage(8192, 4096))
        assertEquals(ImageSize(1, 4096), AttachmentPolicy.fitImage(2, 8192))
    }

    @Test fun purpose_depends_on_server_supported_extension() {
        val attachment = Attachment("attachment-1", "slides.pptx", 10, "application/vnd.openxmlformats-officedocument.presentationml.presentation")
        assertEquals("rich_layout_recognition", attachment.purpose)
        assertEquals("user_data", attachment.copy(name = "readme.md", mediaType = "text/markdown").purpose)
        assertNull(attachment.copy(name = "photo.jpg", mediaType = "image/jpeg").purpose)
    }
}

class OfficialExtensionCatalogTest {
    @Test fun v013_catalog_is_preserved_including_rare_code_formats() {
        assertTrue(AttachmentPolicy.acceptedExtensions.contains(".wxml"))
        assertTrue(AttachmentPolicy.acceptedExtensions.contains(".tga"))
        assertEquals("image/png", AttachmentPolicy.describe("attachment-img", "screen.png",
            byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)).mediaType)
        assertFailsWith<AttachmentFailure> { AttachmentPolicy.normalizeName("payload.exe") }
    }
}
