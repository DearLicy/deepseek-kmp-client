package com.deepseek.client.protocol

/** Incremental WHATWG SSE text decoder. Decode UTF-8 incrementally at the transport boundary. */
class SseDecoder(private val maxEventChars: Int = 4 * 1024 * 1024) {
    private var buffer = ""
    private val dataLines = mutableListOf<String>()
    private var eventType = ""
    private var lastEventId = ""
    private var firstText = true
    private var eventChars = 0

    fun push(text: String): List<SseEvent> {
        val input = if (firstText && text.isNotEmpty()) {
            firstText = false
            text.removePrefix("\uFEFF")
        } else text
        buffer += input
        val events = mutableListOf<SseEvent>()
        var start = 0
        var cursor = 0
        while (cursor < buffer.length) {
            val char = buffer[cursor]
            if (char != '\r' && char != '\n') {
                cursor++
                continue
            }
            if (char == '\r' && cursor == buffer.lastIndex) break
            processLine(buffer.substring(start, cursor))?.let(events::add)
            cursor += if (char == '\r' && buffer.getOrNull(cursor + 1) == '\n') 2 else 1
            start = cursor
        }
        buffer = buffer.substring(start)
        require(buffer.length + eventChars <= maxEventChars) { "SSE event exceeds size limit" }
        return events
    }

    /** A final CR terminates a line. Events without a final blank line are discarded. */
    fun end(): List<SseEvent> {
        val event = if (buffer.endsWith('\r')) processLine(buffer.dropLast(1)) else null
        buffer = ""
        dataLines.clear()
        eventType = ""
        eventChars = 0
        return listOfNotNull(event)
    }

    private fun processLine(line: String): SseEvent? {
        if (line.isEmpty()) {
            val event = if (dataLines.isEmpty()) null else SseEvent(
                event = eventType.ifEmpty { "message" },
                data = dataLines.joinToString("\n"),
                lastEventId = lastEventId,
            )
            dataLines.clear()
            eventType = ""
            eventChars = 0
            return event
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon == -1) line else line.substring(0, colon)
        var value = if (colon == -1) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "data" -> {
                eventChars += value.length + 1
                require(eventChars <= maxEventChars) { "SSE event exceeds size limit" }
                dataLines += value
            }
            "event" -> eventType = value
            "id" -> if (!value.contains('\u0000')) lastEventId = value
        }
        return null
    }
}

data class SseEvent(val event: String, val data: String, val lastEventId: String)
