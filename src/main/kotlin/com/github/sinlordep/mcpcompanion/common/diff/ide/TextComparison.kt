package com.github.sinlordep.mcpcompanion.common.diff.ide

import com.github.sinlordep.mcpcompanion.common.diff.ChangedLines
import com.github.sinlordep.mcpcompanion.common.diff.TextDiff
import com.github.sinlordep.mcpcompanion.common.diff.UnifiedDiff
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.progress.ProgressManager

/**
 * Unified diffs of two texts with the IDE's own line comparison (`ComparisonManager.compareLines`, what the diff
 * viewer shows). Texts too different for it to compare in time are shown as a whole replacement.
 */
object TextComparison {

    /** The diff of [before] and [after] (null: the file does not exist on that side), at most [maxLines] lines. */
    fun diff(oldPath: String?, newPath: String?, before: CharSequence?, after: CharSequence?, maxLines: Int): TextDiff {
        val old = before?.let(UnifiedDiff::normalised).orEmpty()
        val new = after?.let(UnifiedDiff::normalised).orEmpty()
        val left = UnifiedDiff.lines(old)
        val right = UnifiedDiff.lines(new)
        return UnifiedDiff.render(
            oldPath.takeIf { before != null },
            newPath.takeIf { after != null },
            left,
            right,
            changes(old, new, left.size, right.size),
            maxLines,
        )
    }

    /**
     * The comparison counts the empty line after a final line break as a line; [UnifiedDiff.lines] does not, so the
     * blocks are clamped to the line lists.
     */
    private fun changes(before: String, after: String, size1: Int, size2: Int): List<ChangedLines> {
        val indicator = ProgressManager.getInstance().progressIndicator ?: DumbProgressIndicator.INSTANCE
        val blocks = try {
            ComparisonManager.getInstance().compareLines(before, after, ComparisonPolicy.DEFAULT, indicator)
                .map { ChangedLines(it.startLine1, it.endLine1, it.startLine2, it.endLine2) }
        } catch (_: DiffTooBigException) {
            listOf(ChangedLines(0, size1, 0, size2))
        }
        return blocks.map {
            ChangedLines(
                it.start1.coerceAtMost(size1),
                it.end1.coerceAtMost(size1),
                it.start2.coerceAtMost(size2),
                it.end2.coerceAtMost(size2),
            )
        }.filter { it.end1 > it.start1 || it.end2 > it.start2 }
    }
}
