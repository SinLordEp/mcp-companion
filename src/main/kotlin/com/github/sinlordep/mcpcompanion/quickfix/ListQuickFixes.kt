package com.github.sinlordep.mcpcompanion.quickfix

import com.github.sinlordep.mcpcompanion.common.build.ProblemSeverity
import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.paging.page
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap

/**
 * `list`: the problems of a file (optionally of some lines) with the fixes the IDE offers for each. Fixes are worked
 * out for the problems of the requested page only, since checking a fix means computing its change.
 */
class ListQuickFixes(private val gateway: QuickFixGateway) : QuickFixActionHandler {

    override val action = QuickFixAction.LIST
    override val parameters = setOf(
        QuickFixParameter.PATH,
        QuickFixParameter.LINE,
        QuickFixParameter.END_LINE,
        QuickFixParameter.MIN_SEVERITY,
        QuickFixParameter.LIMIT,
        QuickFixParameter.OFFSET,
    )

    override suspend fun handle(request: QuickFixArguments): ToolResult<QuickFixOutcome> {
        val path = request.path?.trim()?.takeIf { it.isNotEmpty() } ?: return missingPath()
        val lines = when (val range = linesOf(request.line, request.endLine)) {
            is ToolResult.Success -> range.value
            is ToolResult.Failure -> return range
        }
        val minimum = request.minSeverity ?: ProblemSeverity.WARNING
        return pageOf(request).flatMap { page ->
            gateway.analyse(path).flatMap { file ->
                val selected = file.problems.filter {
                    it.severity.atLeast(minimum) &&
                        (lines == null || it.touches(lines))
                }
                val problems = selected.asSequence().page(page)
                val withFixes = problems.items.map { problem ->
                    when (val fixes = gateway.fixes(problem)) {
                        is ToolResult.Success -> ProblemFixes(problem, fixes.value.map { it.option(file, problem) })
                        is ToolResult.Failure -> return@flatMap fixes
                    }
                }
                ToolResult.success(
                    QuickFixOutcome.Listed(
                        path = file.path,
                        problems = Page(
                            withFixes,
                            problems.offset,
                            problems.hasMore,
                            problems.nextOffset,
                        ),
                        problemCount = selected.size,
                    ),
                )
            }
        }
    }

    private fun pageOf(request: QuickFixArguments): ToolResult<PageRequest> {
        val limit = request.limit
        if (limit != null && limit > QuickFixControl.MAX_LIMIT) {
            return ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "limit must be between 1 and ${QuickFixControl.MAX_LIMIT}, was $limit",
                "Each listed problem's fixes are computed; page with offset instead.",
            )
        }
        return PageRequest.of(limit, request.offset, QuickFixControl.DEFAULT_LIMIT)
    }

    private fun linesOf(line: Int?, endLine: Int?): ToolResult<IntRange?> = when {
        line == null && endLine == null -> ToolResult.success(null)

        line == null -> ToolResult.failure(ErrorCode.INVALID_ARGUMENT, "endLine needs line.", "Pass line too.")

        line < 1 || (endLine != null && endLine < line) -> ToolResult.failure(
            ErrorCode.INVALID_ARGUMENT,
            "Lines are 1-based and endLine must not be before line (line=$line, endLine=$endLine).",
            "Pass line, and optionally endLine for a range.",
        )

        else -> ToolResult.success(line..(endLine ?: line))
    }
}
