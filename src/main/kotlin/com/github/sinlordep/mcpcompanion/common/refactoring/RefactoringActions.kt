package com.github.sinlordep.mcpcompanion.common.refactoring

import com.github.sinlordep.mcpcompanion.common.actions.ActionHandler
import com.github.sinlordep.mcpcompanion.common.actions.ActionRegistry
import com.github.sinlordep.mcpcompanion.common.paging.page
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocators

/**
 * Gateway boundary of one IDE refactoring with [D] as its details (e.g. a move destination): finds what it would
 * change and what conflicts with it, and carries it out.
 */
interface RefactoringGateway<in D> {

    /** Analyses refactoring [target] with [details] without changing anything. */
    suspend fun analyse(target: LocatedSymbol, details: D): ToolResult<RefactoringAnalysis>

    /**
     * Carries out an analysis without conflicts as one undoable command, unless the code changed since [plan] was
     * made. Callers must only pass plans without conflicts.
     */
    suspend fun apply(plan: RefactoringPlan): ToolResult<RefactoringAttempt>
}

/**
 * The `preview` and `apply` actions shared by the refactoring tools (one [ActionHandler] each). A tool supplies its
 * gateway; [verb] names the refactoring in messages, e.g. "moved".
 */
class RefactoringActions<D>(locators: SymbolLocators, gateway: RefactoringGateway<D>, verb: String) {

    private val actions = ActionRegistry(
        mapOf(
            RefactoringAction.PREVIEW to PreviewRefactoring(locators, gateway),
            RefactoringAction.APPLY to ApplyRefactoring(locators, gateway, verb),
        ),
    )

    suspend fun execute(action: RefactoringAction, request: RefactoringRequest<D>): ToolResult<RefactoringOutcome> =
        actions.dispatch(action, request)

    companion object {
        /** Conflicts a preview lists; the count covers all of them. */
        const val MAX_CONFLICTS: Int = 50
    }
}

/** `preview`: what would change, one page of the affected files, and the conflicts. Changes nothing. */
class PreviewRefactoring<D>(private val locators: SymbolLocators, private val gateway: RefactoringGateway<D>) :
    ActionHandler<RefactoringRequest<D>, RefactoringOutcome> {

    override suspend fun handle(request: RefactoringRequest<D>): ToolResult<RefactoringOutcome> =
        locators.locate(request.symbol).flatMap { target ->
            gateway.analyse(target, request.details).map { analysis ->
                RefactoringOutcome.Previewed(
                    target = target,
                    files = analysis.files.sortedWith(AffectedFile.ORDER).asSequence().page(request.page),
                    fileCount = analysis.files.size,
                    referenceCount = analysis.referenceCount,
                    conflicts = analysis.conflicts.sortedWith(RefactoringConflict.ORDER)
                        .take(RefactoringActions.MAX_CONFLICTS),
                    conflictCount = analysis.conflicts.size,
                )
            }
        }
}

/**
 * `apply`: analyses again and refactors only when nothing conflicts. If the code changes between the analysis and the
 * change, the analysis is repeated, a few times at most; there is no way to refactor despite conflicts.
 */
class ApplyRefactoring<D>(
    private val locators: SymbolLocators,
    private val gateway: RefactoringGateway<D>,
    private val verb: String,
) : ActionHandler<RefactoringRequest<D>, RefactoringOutcome> {

    override suspend fun handle(request: RefactoringRequest<D>): ToolResult<RefactoringOutcome> =
        locators.locate(request.symbol).flatMap { target -> applyAnalysed(target, request.details, MAX_ATTEMPTS) }

    private suspend fun applyAnalysed(
        target: LocatedSymbol,
        details: D,
        attemptsLeft: Int,
    ): ToolResult<RefactoringOutcome> = gateway.analyse(target, details).flatMap { analysis ->
        if (analysis.conflicts.isNotEmpty()) {
            refused(target, analysis)
        } else {
            gateway.apply(analysis.plan).flatMap { attempt ->
                when {
                    attempt is RefactoringAttempt.Applied -> ToolResult.success(
                        RefactoringOutcome.Applied(target, analysis.referenceCount, attempt.changedFiles),
                    )

                    attemptsLeft > 1 -> applyAnalysed(target, details, attemptsLeft - 1)

                    else -> keptChanging(target)
                }
            }
        }
    }

    private fun keptChanging(target: LocatedSymbol): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.CONFLICT,
        "The code kept changing while ${target.name} was being $verb; nothing was changed.",
        "Retry when the project is not being edited.",
    )

    private fun refused(target: LocatedSymbol, analysis: RefactoringAnalysis): ToolResult<Nothing> {
        val first = analysis.conflicts.sortedWith(RefactoringConflict.ORDER).first().message
        return ToolResult.failure(
            ErrorCode.CONFLICT,
            "${target.name} was not $verb: ${analysis.conflicts.size} conflict(s), the first: \"$first\". " +
                "Nothing was changed.",
            "Run action=preview to list them, then change the code or the request so that none remain.",
        )
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
