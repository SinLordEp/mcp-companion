package com.github.sinlordep.mcpcompanion.quickfix

import com.github.sinlordep.mcpcompanion.common.actions.ActionRegistry
import com.github.sinlordep.mcpcompanion.common.actions.ParameterGuard
import com.github.sinlordep.mcpcompanion.common.actions.ParameterizedAction
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/** One `mc_quick_fix` action; arguments outside [parameters] are rejected before [handle]. */
interface QuickFixActionHandler : ParameterizedAction<QuickFixParameter, QuickFixArguments, QuickFixOutcome> {
    val action: QuickFixAction

    override val actionName: String get() = action.name.lowercase()
}

/** Use case of `mc_quick_fix`: dispatches each action to its handler after checking the arguments. */
class QuickFixControl(gateway: QuickFixGateway) {

    private val actions = ActionRegistry(
        listOf(ListQuickFixes(gateway), ApplyQuickFix(gateway))
            .associate { it.action to ParameterGuard(it, QuickFixParameter::label) },
    )

    suspend fun execute(action: QuickFixAction, arguments: QuickFixArguments): ToolResult<QuickFixOutcome> =
        actions.dispatch(action, arguments)

    companion object {
        const val DEFAULT_LIMIT: Int = 20
        const val MAX_LIMIT: Int = 100
    }
}

internal fun AnalysedFix.option(file: AnalysedFile, problem: AnalysedProblem): FixOption =
    FixOption(FixId.of(file, problem, this), name, refusal)

internal fun missingPath(): ToolResult<Nothing> = ToolResult.failure(
    ErrorCode.INVALID_ARGUMENT,
    "path is required: the file whose problems to fix.",
    "Pass the file's path relative to the project root, or an absolute path.",
)
