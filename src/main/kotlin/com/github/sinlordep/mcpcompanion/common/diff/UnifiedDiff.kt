package com.github.sinlordep.mcpcompanion.common.diff

/**
 * One changed block between two versions of a text: lines `[start1, end1)` of the old version became lines
 * `[start2, end2)` of the new one (0-based, end exclusive; an empty range is an insertion or a deletion).
 */
data class ChangedLines(val start1: Int, val end1: Int, val start2: Int, val end2: Int)

/** A unified diff, cut after [maxLines] lines when [truncated]. [added] and [removed] count all changed lines. */
data class TextDiff(val text: String, val added: Int, val removed: Int, val truncated: Boolean) {
    val isEmpty: Boolean get() = added == 0 && removed == 0
}

/**
 * Renders changed blocks as a unified diff (`git diff` style: `--- a/path`, `+++ b/path`, `@@ -l,n +l,n @@` hunks with
 * [CONTEXT] lines around each change). Pure: the blocks come from the IDE's line comparison.
 */
object UnifiedDiff {

    const val CONTEXT: Int = 3

    /**
     * The diff of [before] and [after] (texts as lines, without line breaks) for the [changes] between them, headed by
     * [oldPath] and [newPath] (null for a created or deleted file: `/dev/null`). At most [maxLines] lines of output.
     */
    @Suppress("LongParameterList") // The two versions, their names, the changes and the limit.
    fun render(
        oldPath: String?,
        newPath: String?,
        before: List<String>,
        after: List<String>,
        changes: List<ChangedLines>,
        maxLines: Int,
    ): TextDiff {
        val added = changes.sumOf { it.end2 - it.start2 }
        val removed = changes.sumOf { it.end1 - it.start1 }
        if (changes.isEmpty()) return TextDiff("", 0, 0, false)
        val header =
            sequenceOf("--- ${oldPath?.let { "a/$it" } ?: DEV_NULL}", "+++ ${newPath?.let { "b/$it" } ?: DEV_NULL}")
        val body = hunks(changes, before.size, after.size).asSequence()
            .flatMap { sequenceOf(it.header()) + it.lines(before, after) }
        val all = header + body
        val kept = all.take(maxLines + 1).toList()
        val truncated = kept.size > maxLines
        val text = kept.take(maxLines).joinToString("\n") { it.take(MAX_LINE_LENGTH) }
        return TextDiff(text, added, removed, truncated)
    }

    /** [text] with `\n` line breaks only, as the IDE's documents hold text. */
    fun normalised(text: CharSequence): String = text.toString().replace("\r\n", "\n").replace('\r', '\n')

    /** The lines of a [normalised] text; a final line break ends the last line and starts no new one. */
    fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split('\n')
        return if (lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    private fun hunks(changes: List<ChangedLines>, size1: Int, size2: Int): List<Hunk> {
        val groups = ArrayList<MutableList<ChangedLines>>()
        for (change in changes.sortedBy { it.start1 }) {
            val last = groups.lastOrNull()?.last()
            if (last != null &&
                change.start1 - last.end1 <= 2 * CONTEXT
            ) {
                groups.last() += change
            } else {
                groups += mutableListOf(change)
            }
        }
        return groups.map { Hunk(it, size1, size2) }
    }

    private class Hunk(val changes: List<ChangedLines>, size1: Int, size2: Int) {
        private val first = changes.first()
        private val last = changes.last()
        val from1 = (first.start1 - CONTEXT).coerceAtLeast(0)
        val from2 = first.start2 - (first.start1 - from1)
        val to1 = (last.end1 + CONTEXT).coerceAtMost(size1)
        val to2 = (last.end2 + (to1 - last.end1)).coerceAtMost(size2)

        fun header(): String = "@@ -${range(from1, to1)} +${range(from2, to2)} @@"

        private fun range(from: Int, to: Int): String {
            val count = to - from
            val start = if (count == 0) from else from + 1
            return if (count == 1) "$start" else "$start,$count"
        }

        fun lines(before: List<String>, after: List<String>): Sequence<String> = sequence {
            var line1 = from1
            for (change in changes) {
                while (line1 < change.start1) yield(" " + before[line1++])
                for (i in change.start1 until change.end1) yield("-" + before[i])
                for (i in change.start2 until change.end2) yield("+" + after[i])
                line1 = change.end1
            }
            while (line1 < to1) yield(" " + before[line1++])
        }
    }

    private const val DEV_NULL = "/dev/null"

    /** Longer lines (minified files) are cut, so that one line cannot fill the answer. */
    private const val MAX_LINE_LENGTH = 1_000
}
