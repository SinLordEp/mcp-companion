package com.github.sinlordep.mcpcompanion.run.output

/** Which stream of a process a line came from. `SYSTEM` lines are written by the IDE (command line, exit code). */
enum class ConsoleStream { STDOUT, STDERR, SYSTEM }

/**
 * One complete line of console output, ANSI escape sequences removed. [seq] numbers the lines of one process from 1
 * upwards, without gaps; it is the cursor clients poll with.
 */
data class ConsoleLine(val seq: Long, val stream: ConsoleStream, val text: String)

/** Bounds of one process's [ConsoleBuffer]. The oldest lines are dropped first when a bound is reached. */
data class ConsoleLimits(val maxLines: Int, val maxChars: Int, val maxLineChars: Int) {
    init {
        require(maxLines > 0 && maxChars > 0 && maxLineChars > 0) { "limits must be positive: $this" }
    }

    companion object {
        /** About 4 MB of text per process at most. */
        val DEFAULT: ConsoleLimits = ConsoleLimits(maxLines = 10_000, maxChars = 2_000_000, maxLineChars = 8_000)
    }
}

/**
 * The retained lines after [ConsoleBuffer.read]'s cursor or its tail. [firstSeq] is the oldest line still retained
 * (0 when there is none); [lastSeq] the newest line written so far. A client that polls with `since` older than
 * `firstSeq - 1` has missed [missed] lines that were dropped to stay within the bounds.
 */
data class ConsoleSlice(
    val lines: List<ConsoleLine>,
    val nextCursor: Long,
    val firstSeq: Long,
    val lastSeq: Long,
    val missed: Long,
    val hasMore: Boolean,
)
