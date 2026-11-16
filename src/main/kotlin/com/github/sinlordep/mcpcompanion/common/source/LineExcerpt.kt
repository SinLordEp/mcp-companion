package com.github.sinlordep.mcpcompanion.common.source

/**
 * Shortens a source line for output: surrounding whitespace is removed, and a line longer than [maxLength] is cut to a
 * window around the 1-based [column], with `…` marking each cut.
 */
object LineExcerpt {

    const val MAX_LENGTH: Int = 160
    private const val ELLIPSIS = "…"

    fun of(line: String, column: Int, maxLength: Int = MAX_LENGTH): String {
        require(maxLength > 2 * ELLIPSIS.length) { "maxLength too small: $maxLength" }
        val start = line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return ""
        val end = line.indexOfLast { !it.isWhitespace() } + 1
        return if (end - start <= maxLength) line.substring(start, end) else window(line, start, end, column, maxLength)
    }

    private fun window(line: String, start: Int, end: Int, column: Int, maxLength: Int): String {
        val room = maxLength - 2 * ELLIPSIS.length
        val focus = (column - 1).coerceIn(start, end - 1)
        val from = (focus - room / 2).coerceIn(start, end - room)
        val to = from + room
        return (if (from > start) ELLIPSIS else "") + line.substring(from, to) + (if (to < end) ELLIPSIS else "")
    }
}
