package com.github.sinlordep.mcpcompanion.vcshistory

import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.paging.page
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map

/**
 * `log`: the newest revisions of a file or directory, one page. With `line` (and `endLine`), the revisions that last
 * changed those lines according to the annotation, newest first: who wrote the lines as they are now, not every
 * commit that ever touched them.
 */
class LogRevisions(private val gateway: VcsHistoryGateway) : VcsHistoryActionHandler {

    override val action = VcsHistoryAction.LOG
    override val parameters = setOf(
        VcsHistoryParameter.PATH,
        VcsHistoryParameter.LINE,
        VcsHistoryParameter.END_LINE,
        VcsHistoryParameter.LIMIT,
        VcsHistoryParameter.OFFSET,
    )

    override suspend fun handle(request: VcsHistoryArguments): ToolResult<VcsHistoryOutcome> =
        requiredPath(request).flatMap { path ->
            logPage(request).flatMap { page ->
                if (request.line == null && request.endLine == null) {
                    gateway.log(path, page.offset + page.limit).map { log ->
                        val items = log.revisions.drop(page.offset).take(page.limit)
                        VcsHistoryOutcome.Logged(log.path, log.vcs, null, Page(items, page.offset, log.hasMore))
                    }
                } else {
                    gateway.blame(path).flatMap { blame ->
                        lineRange(request, blame.lines.size).map { lines ->
                            val commits = commitsOf(blame, lines).sortedByDescending { it.date ?: Long.MIN_VALUE }
                            VcsHistoryOutcome.Logged(blame.path, blame.vcs, lines, commits.asSequence().page(page))
                        }
                    }
                }
            }
        }

    private fun logPage(request: VcsHistoryArguments): ToolResult<PageRequest> =
        PageRequest.of(request.limit, request.offset, VcsHistoryControl.DEFAULT_LOG_LIMIT).flatMap { page ->
            if (page.limit <= VcsHistoryControl.MAX_LOG_LIMIT && page.offset <= MAX_LOG_OFFSET) {
                ToolResult.success(page)
            } else {
                ToolResult.failure(
                    ErrorCode.INVALID_ARGUMENT,
                    "log takes limit 1-${VcsHistoryControl.MAX_LOG_LIMIT} and offset 0-$MAX_LOG_OFFSET.",
                    "Narrow the history with line/endLine or a deeper path instead.",
                )
            }
        }

    private fun commitsOf(blame: FileBlame, lines: IntRange): List<CommitInfo> = lines.mapNotNull {
        blame.lines[it - 1]
    }.distinct().map { blame.commits[it] ?: CommitInfo(it, null, null, null) }

    private companion object {
        const val MAX_LOG_OFFSET = 10_000
    }
}

/**
 * `blame`: who last changed each line, as segments of consecutive lines from the same revision (optionally of a line
 * range), one page, with the commits they name.
 */
class BlameLines(private val gateway: VcsHistoryGateway) : VcsHistoryActionHandler {

    override val action = VcsHistoryAction.BLAME
    override val parameters = setOf(
        VcsHistoryParameter.PATH,
        VcsHistoryParameter.LINE,
        VcsHistoryParameter.END_LINE,
        VcsHistoryParameter.LIMIT,
        VcsHistoryParameter.OFFSET,
    )

    override suspend fun handle(request: VcsHistoryArguments): ToolResult<VcsHistoryOutcome> =
        requiredPath(request).flatMap { path ->
            PageRequest.of(request.limit, request.offset, VcsHistoryControl.DEFAULT_BLAME_LIMIT).flatMap { page ->
                gateway.blame(path).flatMap { blame ->
                    lineRange(request, blame.lines.size).map { lines ->
                        val segments = segments(blame.lines, lines).asSequence().page(page)
                        val commits = segments.items.mapNotNull { it.revision }.distinct()
                            .map { blame.commits[it] ?: CommitInfo(it, null, null, null) }
                        VcsHistoryOutcome.Blamed(blame, lines, segments, commits)
                    }
                }
            }
        }

    private fun segments(revisions: List<String?>, lines: IntRange): List<BlameSegment> {
        val result = ArrayList<BlameSegment>()
        if (lines.isEmpty()) return result
        var start = lines.first
        for (line in lines.first + 1..lines.last + 1) {
            if (line > lines.last || revisions[line - 1] != revisions[start - 1]) {
                result += BlameSegment(start, line - 1, revisions[start - 1])
                start = line
            }
        }
        return result
    }
}

/**
 * `diff`: one file between a revision (default: the one the working copy is based on) and another revision or the
 * working copy.
 */
class DiffRevisions(private val gateway: VcsHistoryGateway) : VcsHistoryActionHandler {

    override val action = VcsHistoryAction.DIFF
    override val parameters = setOf(
        VcsHistoryParameter.PATH,
        VcsHistoryParameter.FROM,
        VcsHistoryParameter.TO,
        VcsHistoryParameter.MAX_LINES,
    )

    override suspend fun handle(request: VcsHistoryArguments): ToolResult<VcsHistoryOutcome> =
        requiredPath(request).flatMap { path ->
            val maxLines = request.maxLines ?: VcsHistoryControl.DEFAULT_MAX_LINES
            if (maxLines !in 1..VcsHistoryControl.MAX_LINES) {
                ToolResult.failure(
                    ErrorCode.INVALID_ARGUMENT,
                    "maxLines must be between 1 and ${VcsHistoryControl.MAX_LINES}, was $maxLines.",
                )
            } else if (request.from == null && request.to != null) {
                ToolResult.failure(
                    ErrorCode.INVALID_ARGUMENT,
                    "to needs from: the diff between two revisions names both.",
                    "Pass from=<older revision> to=<newer revision>, or only from to compare with the working copy.",
                )
            } else {
                gateway.diff(path, request.from?.trim(), request.to?.trim(), maxLines)
                    .map { VcsHistoryOutcome.Diffed(it) }
            }
        }
}

private fun requiredPath(request: VcsHistoryArguments): ToolResult<String> =
    request.path?.takeIf { it.isNotBlank() }?.let { ToolResult.success(it) } ?: ToolResult.failure(
        ErrorCode.INVALID_ARGUMENT,
        "path is required.",
        "Pass a path relative to the project root or an absolute path.",
    )

/** The 1-based lines [VcsHistoryArguments.line]..[VcsHistoryArguments.endLine] of a file of [lineCount] lines. */
private fun lineRange(request: VcsHistoryArguments, lineCount: Int): ToolResult<IntRange> {
    val first = request.line ?: if (request.endLine == null) {
        1
    } else {
        return ToolResult.failure(
            ErrorCode.INVALID_ARGUMENT,
            "endLine needs line.",
            "Pass line (and optionally endLine) to name the range.",
        )
    }
    val last = request.endLine ?: if (request.line == null) lineCount else first
    return when {
        lineCount == 0 -> ToolResult.success(IntRange.EMPTY)

        first < 1 || last < first -> ToolResult.failure(
            ErrorCode.INVALID_ARGUMENT,
            "line must be at least 1 and endLine at least line (got $first..$last).",
        )

        first > lineCount -> ToolResult.failure(
            ErrorCode.INVALID_ARGUMENT,
            "line $first is beyond the annotated version's $lineCount lines.",
            "Line numbers are those of the committed version; see annotatedRevision.",
        )

        else -> ToolResult.success(first..last.coerceAtMost(lineCount))
    }
}
