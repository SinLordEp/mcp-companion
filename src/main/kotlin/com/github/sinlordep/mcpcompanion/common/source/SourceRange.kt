package com.github.sinlordep.mcpcompanion.common.source

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/**
 * A stretch of a file as a client names it, e.g. the code to extract into a method: from [startLine]:[startColumn]
 * up to, not including, [endLine]:[endColumn], 1-based as editors show them. [endColumn] null means to the end of
 * [endLine]. [path] is project-relative or absolute.
 */
@ConsistentCopyVisibility
data class SourceRange private constructor(
    val path: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int?,
) {
    /** For messages, e.g. "lines 12-14 of src/shop/Cart.java". */
    val description: String
        get() = if (startLine == endLine) "line $startLine of $path" else "lines $startLine-$endLine of $path"

    companion object {
        private const val HOW = "Pass path, startLine and endLine (1-based, the end line included), optionally " +
            "startColumn (default 1) and endColumn (exclusive; default: the end of endLine)."

        /** Validates client input; a blank [path] counts as absent, [startColumn] defaults to 1. */
        fun of(
            path: String?,
            startLine: Int?,
            startColumn: Int?,
            endLine: Int?,
            endColumn: Int?,
        ): ToolResult<SourceRange> {
            val file = path?.trim()?.takeIf { it.isNotEmpty() } ?: return invalid("path is missing.")
            if (startLine == null || endLine == null) return invalid("startLine and endLine are required.")
            val range = SourceRange(file, startLine, startColumn ?: 1, endLine, endColumn)
            return range.problem()?.let(::invalid) ?: ToolResult.success(range)
        }

        private fun invalid(problem: String): ToolResult<Nothing> =
            ToolResult.failure(ErrorCode.INVALID_ARGUMENT, problem, HOW)

        private fun SourceRange.problem(): String? = when {
            minOf(startLine, endLine, startColumn, endColumn ?: 1) < 1 -> "Lines and columns are 1-based."

            endLine < startLine -> "endLine $endLine is before startLine $startLine."

            endLine == startLine && endColumn != null && endColumn <= startColumn ->
                "endColumn $endColumn must be after startColumn $startColumn on the same line."

            else -> null
        }
    }
}
