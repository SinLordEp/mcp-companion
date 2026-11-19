package com.github.sinlordep.mcpcompanion.quickfix

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap

/**
 * `apply`: analyses the file again, finds the fix by its id and applies it as one undoable command. An id that matches
 * nothing (the file changed since `list`, or the problem is gone) changes nothing; there is no "fix all".
 */
class ApplyQuickFix(private val gateway: QuickFixGateway) : QuickFixActionHandler {

    override val action = QuickFixAction.APPLY
    override val parameters = setOf(QuickFixParameter.PATH, QuickFixParameter.FIX)

    override suspend fun handle(request: QuickFixArguments): ToolResult<QuickFixOutcome> {
        val path = request.path?.trim()?.takeIf { it.isNotEmpty() } ?: return missingPath()
        val text = request.fix?.trim()?.takeIf { it.isNotEmpty() } ?: return ToolResult.failure(
            ErrorCode.INVALID_ARGUMENT,
            "action=apply needs fix, the id of a fix from action=list.",
            "Call action=list for $path and pass one of its fix ids.",
        )
        val id = FixId.parse(text) ?: return ToolResult.failure(
            ErrorCode.INVALID_ARGUMENT,
            "'$text' is not a fix id; ids look like 12:9/2-3fa1b2c4.",
            "Pass an id exactly as action=list returned it.",
        )
        return gateway.analyse(path).flatMap { file -> find(file, id) }.flatMap { (problem, fix, option) ->
            option.refusal?.let { reason -> return@flatMap refused(option, reason) }
            gateway.apply(fix).flatMap { attempt ->
                when (attempt) {
                    is FixAttempt.Applied -> ToolResult.success(
                        QuickFixOutcome.Applied(path, problem, option, attempt.changedFiles),
                    )

                    FixAttempt.Stale -> stale(path, id)
                }
            }
        }
    }

    private suspend fun find(
        file: AnalysedFile,
        id: FixId,
    ): ToolResult<Triple<AnalysedProblem, AnalysedFix, FixOption>> {
        for (problem in file.problems.filter { it.line == id.line && it.column == id.column }) {
            matchIn(file, problem, id)?.let { return it }
        }
        return stale(file.path, id)
    }

    /** The fix [id] among the fixes of [problem], a failure to compute them, or null when it is not there. */
    private suspend fun matchIn(
        file: AnalysedFile,
        problem: AnalysedProblem,
        id: FixId,
    ): ToolResult<Triple<AnalysedProblem, AnalysedFix, FixOption>>? = when (val found = gateway.fixes(problem)) {
        is ToolResult.Failure -> found

        is ToolResult.Success -> found.value.firstOrNull { it.option(file, problem).id == id }
            ?.let { fix -> ToolResult.success(Triple(problem, fix, fix.option(file, problem))) }
    }

    private fun refused(fix: FixOption, reason: String): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.UNSUPPORTED,
        "The fix '${fix.name}' cannot be applied without the user: $reason. Nothing was changed.",
        "Choose another fix of the problem, change the code yourself, or ask the user to apply it in the IDE " +
            "(Alt+Enter).",
    )

    private fun stale(path: String, id: FixId): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.CONFLICT,
        "No fix $id in $path now: the file changed since action=list, or the problem is gone. Nothing was changed.",
        "Call action=list again and use an id from that answer.",
    )
}
