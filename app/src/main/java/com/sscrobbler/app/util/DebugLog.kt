package com.sscrobbler.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestampMs: Long,
    val tag: String,
    val message: String
)

/**
 * In-memory ring buffer of diagnostic events.
 *
 * Kept intentionally small and dependency-free: the debug screen reads [entries]
 * and renders the last [MAX_ENTRIES] lines. Nothing secret is ever logged here
 * (no session keys, no API secret), only track metadata and API outcome codes.
 */
object DebugLog {
    private const val MAX_ENTRIES = 800

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), tag, message)
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
            _entries.value = buffer.toList()
        }
        try {
            android.util.Log.d("sScrobbler/$tag", message)
        } catch (_: Throwable) {
            // android.util.Log is not available in plain JVM unit tests
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    fun export(): String {
        val snapshot = entries.value
        if (snapshot.isEmpty()) return "sScrobbler debug log is empty"
        return snapshot.joinToString("\n") { entry ->
            "${timeFormat.format(Date(entry.timestampMs))} [${entry.tag}] ${entry.message}"
        }
    }
}
