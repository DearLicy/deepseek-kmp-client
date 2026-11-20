package com.deepseek.client.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SseDecoderTest {
    @Test
    fun parses_lf_events_and_ignores_comments() {
        val decoder = SseDecoder()
        val events = decoder.push(
            ": keep-alive\n" +
                "event: message_start\n" +
                "data: {\"type\":\"message_start\"}\n" +
                "\n" +
                "data: first\n" +
                "data: second\n" +
                "\n",
        )
        assertEquals(2, events.size)
        assertEquals("message_start", events[0].event)
        assertEquals("{\"type\":\"message_start\"}", events[0].data)
        assertEquals("message", events[1].event)
        assertEquals("first\nsecond", events[1].data)
    }

    @Test
    fun handles_split_crlf() {
        val decoder = SseDecoder()
        val first = decoder.push("event: ping\r\ndata: {\"type\":\"ping\"}\r\n\r")
        assertTrue(first.isEmpty())
        val second = decoder.push("\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
        assertEquals(2, second.size)
        assertEquals("ping", second[0].event)
        assertEquals("message_stop", second[1].event)
    }

    @Test
    fun end_flushes_trailing_cr_but_discards_unfinished_event() {
        val decoder = SseDecoder()
        decoder.push("data: unfinished\n")
        val events = decoder.end()
        assertTrue(events.isEmpty())
    }

    @Test
    fun every_chunk_boundary_preserves_events() {
        val sample = "\uFEFF: keepalive\r\nevent: text\r\nid: 7\r\ndata: 你好\r\ndata: 世界\r\n\r\ndata:\r\n\r\n"
        val expected = listOf(SseEvent("text", "你好\n世界", "7"), SseEvent("message", "", "7"))
        for (split in 0..sample.length) {
            val decoder = SseDecoder()
            val actual = decoder.push(sample.take(split)) + decoder.push(sample.drop(split)) + decoder.end()
            assertEquals(expected, actual, "split=$split")
        }
        val decoder = SseDecoder()
        val actual = sample.flatMap { decoder.push(it.toString()) } + decoder.end()
        assertEquals(expected, actual)
    }

    @Test
    fun cr_lines_and_final_blank_cr_dispatch() {
        val decoder = SseDecoder()
        assertEquals(listOf(SseEvent("message", "x", "")), decoder.push("data: x\r\r") + decoder.end())
    }

    @Test
    fun invalid_id_is_ignored_and_event_type_resets_without_data() {
        val decoder = SseDecoder()
        val events = decoder.push("id: okay\nevent: unused\n\nid: bad\u0000id\ndata: x\n\n")
        assertEquals(listOf(SseEvent("message", "x", "okay")), events)
    }

    @Test
    fun oversized_event_fails_explicitly() {
        assertFailsWith<IllegalArgumentException> { SseDecoder(10).push("data: " + "x".repeat(100)) }
    }
}
