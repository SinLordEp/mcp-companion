package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.run.output.ConsoleQuery

/** `processes`: every retained process of the project, most recently started first. */
class ListProcesses(private val catalog: ProcessCatalog) : RunActionHandler {

    override val action = RunAction.PROCESSES
    override val parameters = emptySet<RunParameter>()

    override suspend fun handle(request: RunArguments): ToolResult<RunOutcome> =
        ToolResult.success(RunOutcome.Processes(catalog.processes().map { it.snapshot }))
}

/** `status`: the state of one process. */
class ProcessStatus(private val processes: ProcessResolver) : RunActionHandler {

    override val action = RunAction.STATUS
    override val parameters = setOf(RunParameter.PROCESS, RunParameter.CONFIGURATION, RunParameter.TYPE)

    override suspend fun handle(request: RunArguments): ToolResult<RunOutcome> =
        processes.resolve(request).flatMap { ToolResult.success(RunOutcome.Process(it.snapshot)) }
}

/**
 * `output`: the tail of the captured console, or the lines after a cursor, optionally filtered by a regular
 * expression.
 */
class ReadOutput(private val processes: ProcessResolver) : RunActionHandler {

    override val action = RunAction.OUTPUT
    override val parameters = setOf(
        RunParameter.PROCESS,
        RunParameter.CONFIGURATION,
        RunParameter.TYPE,
        RunParameter.LINES,
        RunParameter.SINCE,
        RunParameter.PATTERN,
    )

    override suspend fun handle(request: RunArguments): ToolResult<RunOutcome> = query(request).flatMap { query ->
        processes.resolve(request).flatMap { process -> read(process, query) }
    }

    private fun read(process: TrackedProcess, query: ConsoleQuery): ToolResult<RunOutcome> {
        val slice = process.console.read(query)
        val since = query.since
        if (since != null && since > slice.lastSeq) {
            return ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "since=$since is beyond the last line of ${process.id} (${slice.lastSeq}).",
                "Pass a nextCursor this process returned, or omit since for the tail.",
            )
        }
        return ToolResult.success(RunOutcome.Output(process.snapshot, slice))
    }

    private fun query(request: RunArguments): ToolResult<ConsoleQuery> {
        val lines = request.lines ?: DEFAULT_LINES
        val since = request.since
        return when {
            lines !in 1..MAX_LINES -> ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "lines must be between 1 and $MAX_LINES, was $lines.",
                "Omit it for the last $DEFAULT_LINES lines.",
            )

            since != null && since < 0 -> ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "since must not be negative.",
                "Use 0 to read from the first retained line.",
            )

            else -> RunPatterns.compile(request.pattern, RunParameter.PATTERN).flatMap { filter ->
                ToolResult.success(ConsoleQuery(lines, since, filter))
            }
        }
    }

    private companion object {
        const val DEFAULT_LINES = 50
        const val MAX_LINES = 500
    }
}

/** Client regular expressions: bounded length, compiled once, syntax errors reported as invalid arguments. */
internal object RunPatterns {
    private const val MAX_LENGTH = 500

    fun compile(pattern: String?, parameter: RunParameter): ToolResult<Regex?> {
        if (pattern == null) return ToolResult.success(null)
        if (pattern.isEmpty() || pattern.length > MAX_LENGTH) {
            return ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "${parameter.label} must have 1 to $MAX_LENGTH characters.",
                null,
            )
        }
        return try {
            ToolResult.success(Regex(pattern))
        } catch (e: IllegalArgumentException) {
            ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "${parameter.label} is not a valid regular expression: ${e.message?.lineSequence()?.first()}",
                "Use Java regular expression syntax; it is matched against each line (find, not full match).",
            )
        }
    }
}
