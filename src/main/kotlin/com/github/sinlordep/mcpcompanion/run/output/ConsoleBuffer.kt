package com.github.sinlordep.mcpcompanion.run.output

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.EnumMap

/**
 * Bounded console history of one process, fed by a process listener and read by `output` and `wait_ready`
 * (observer pattern: readers watch [version] and re-read from their cursor, so they never miss a line that is still
 * retained and never need an unbounded queue).
 *
 * Lines are numbered without gaps; when [ConsoleLimits] are exceeded the oldest lines are dropped. Thread-safe: the
 * process's reader threads append while MCP calls read.
 */
class ConsoleBuffer(private val limits: ConsoleLimits = ConsoleLimits.DEFAULT) {

    private val lock = Any()
    private val lines = ArrayDeque<ConsoleLine>()
    private val assemblers = EnumMap<ConsoleStream, LineAssembler>(ConsoleStream::class.java)
    private var retainedChars = 0L
    private var lastSeq = 0L
    private var closed = false
    private val changes = MutableStateFlow(0L)

    /** Changes whenever text arrives or the buffer is closed; a signal to re-read, not a line count. */
    val version: StateFlow<Long> = changes.asStateFlow()

    /** Adds output of [stream]; complete lines become readable at once. Ignored after [close]. */
    fun append(text: CharSequence, stream: ConsoleStream) {
        synchronized(lock) {
            if (closed) return
            assemblers.getOrPut(stream) { LineAssembler(limits.maxLineChars) }.feed(text).forEach { add(stream, it) }
            changes.value++
        }
    }

    /** The process ended: unfinished lines become complete lines, later text is ignored. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            ConsoleStream.entries.forEach { stream -> assemblers[stream]?.flush()?.let { add(stream, it) } }
            closed = true
            changes.value++
        }
    }

    val isClosed: Boolean get() = synchronized(lock) { closed }

    /** One page of lines, see [ConsoleQuery]. */
    fun read(query: ConsoleQuery): ConsoleSlice = synchronized(lock) {
        val firstSeq = lines.firstOrNull()?.seq ?: 0L
        if (query.since == null) tail(query, firstSeq) else after(query, query.since, firstSeq)
    }

    /** Everything retained after [cursor] plus the unfinished line of each stream; for readiness checks. */
    fun snapshotAfter(cursor: Long): ConsoleSnapshot = synchronized(lock) {
        val firstSeq = lines.firstOrNull()?.seq ?: 0L
        val start = (cursor + 1 - firstSeq).coerceIn(0L, lines.size.toLong()).toInt()
        val partials = ConsoleStream.entries.mapNotNull { stream ->
            assemblers[stream]?.partial()?.let { ConsoleLine(lastSeq + 1, stream, it) }
        }
        ConsoleSnapshot(lines.subList(start, lines.size).toList(), partials, lastSeq, closed)
    }

    private fun add(stream: ConsoleStream, text: String) {
        lines.addLast(ConsoleLine(++lastSeq, stream, text))
        retainedChars += text.length
        while (lines.size > limits.maxLines || retainedChars > limits.maxChars) {
            retainedChars -= lines.removeFirst().text.length
        }
    }

    private fun tail(query: ConsoleQuery, firstSeq: Long): ConsoleSlice {
        // One line more than asked tells whether there are more.
        val matching = lines.asReversed().asSequence().filter(query::matches).take(query.limit + 1).toList()
        val picked = matching.take(query.limit).asReversed()
        return ConsoleSlice(picked, lastSeq, firstSeq, lastSeq, missed = 0, hasMore = matching.size > query.limit)
    }

    private fun after(query: ConsoleQuery, since: Long, firstSeq: Long): ConsoleSlice {
        val missed = if (lines.isEmpty()) maxOf(0L, lastSeq - since) else maxOf(0L, firstSeq - since - 1)
        val start = (since + 1 - firstSeq).coerceIn(0L, lines.size.toLong()).toInt()
        val matching = (start until lines.size).asSequence().map(lines::get).filter(query::matches)
            .take(query.limit + 1).toList()
        val picked = matching.take(query.limit)
        val hasMore = matching.size > query.limit
        // Without more matches the reader has seen everything up to the last line, matching or not.
        val cursor = if (hasMore) picked.last().seq else maxOf(lastSeq, since)
        return ConsoleSlice(picked, cursor, firstSeq, lastSeq, missed, hasMore)
    }
}

/**
 * What to read: the last [limit] lines (when [since] is null) or the first [limit] lines after the cursor [since];
 * only lines matching [filter], when given.
 */
data class ConsoleQuery(val limit: Int, val since: Long? = null, val filter: Regex? = null) {
    fun matches(line: ConsoleLine): Boolean = filter == null || filter.containsMatchIn(line.text)
}

/** See [ConsoleBuffer.snapshotAfter]. Partial lines carry the number the line will get when it completes. */
data class ConsoleSnapshot(
    val lines: List<ConsoleLine>,
    val partials: List<ConsoleLine>,
    val lastSeq: Long,
    val closed: Boolean,
)
