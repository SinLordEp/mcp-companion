package com.github.sinlordep.mcpcompanion.common.refactoring

import com.github.sinlordep.mcpcompanion.common.actions.ActionHandler
import com.github.sinlordep.mcpcompanion.common.actions.ActionRegistry
import com.github.sinlordep.mcpcompanion.common.paging.page
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.source.SourceRange
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocators
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery

/**
 * How a refactoring tool finds what it refactors, [T], from what the client names, [Q]: a declaration from a symbol
 * query ([SymbolTargets]), a code range from its positions.
 */
interface RefactoringTargets<in Q, T> {

    suspend fun locate(query: Q): ToolResult<T>

    /** Names [target] in messages, e.g. "Order". */
    fun nameOf(target: T): String
}

/** Refactoring targets that are declarations, found by the shared symbol locators. */
class SymbolTargets(private val locators: SymbolLocators) : RefactoringTargets<SymbolQuery, LocatedSymbol> {

    override suspend fun locate(query: SymbolQuery): ToolResult<LocatedSymbol> = locators.locate(query)

    override fun nameOf(target: LocatedSymbol): String = target.name
}

/**
 * Refactoring targets that are code given by its positions. The range is taken as the client gave it; the gateway
 * checks it against the file, inside the same read action as the analysis.
 */
object SourceRangeTargets : RefactoringTargets<SourceRange, SourceRange> {

    override suspend fun locate(query: SourceRange): ToolResult<SourceRange> = ToolResult.success(query)

    override fun nameOf(target: SourceRange): String = "The code at ${target.description}"
}

/**
 * Gateway boundary of one IDE refactoring of a [T] (a declaration, a code range) with [D] as its details (e.g. a move
 * destination): finds what it would change and what conflicts with it, and carries it out.
 */
interface RefactoringGateway<in T, in D> {

    /** Analyses refactoring [target] with [details] without changing anything. */
    suspend fun analyse(target: T, details: D): ToolResult<RefactoringAnalysis>

    /**
     * Carries out an analysis without conflicts as one undoable command, unless the code changed since [plan] was
     * made. Callers must only pass plans without conflicts.
     */
    suspend fun apply(plan: RefactoringPlan): ToolResult<RefactoringAttempt>
}

/**
 * The `preview` and `apply` actions shared by the refactoring tools (one [ActionHandler] each). A tool supplies how
 * its targets are found and its gateway; [verb] names the refactoring in messages, e.g. "moved".
 */
class RefactoringActions<Q, T, D>(targets: RefactoringTargets<Q, T>, gateway: RefactoringGateway<T, D>, verb: String) {

    private val actions = ActionRegistry(
        mapOf(
            RefactoringAction.PREVIEW to PreviewRefactoring(targets, gateway),
            RefactoringAction.APPLY to ApplyRefactoring(targets, gateway, verb),
        ),
    )

    suspend fun execute(
        action: RefactoringAction,
        request: RefactoringRequest<Q, D>,
    ): ToolResult<RefactoringOutcome<T>> = actions.dispatch(action, request)

    companion object {
        /** Conflicts a preview lists; the count covers all of them. */
        const val MAX_CONFLICTS: Int = 50
    }
}

/** `preview`: what would change, one page of the affected files, and the conflicts. Changes nothing. */
class PreviewRefactoring<Q, T, D>(
    private val targets: RefactoringTargets<Q, T>,
    private val gateway: RefactoringGateway<T, D>,
) : ActionHandler<RefactoringRequest<Q, D>, RefactoringOutcome<T>> {

    override suspend fun handle(request: RefactoringRequest<Q, D>): ToolResult<RefactoringOutcome<T>> =
        targets.locate(request.target).flatMap { target ->
            gateway.analyse(target, request.details).map { analysis ->
                RefactoringOutcome.Previewed(
                    target = target,
                    files = analysis.files.sortedWith(AffectedFile.ORDER).asSequence().page(request.page),
                    fileCount = analysis.files.size,
                    referenceCount = analysis.referenceCount,
                    conflicts = analysis.conflicts.sortedWith(RefactoringConflict.ORDER)
                        .take(RefactoringActions.MAX_CONFLICTS),
                    conflictCount = analysis.conflicts.size,
                    summary = analysis.summary,
                )
            }
        }
}

/**
 * `apply`: analyses again and refactors only when nothing conflicts. If the code changes between the analysis and the
 * change, the analysis is repeated, a few times at most; there is no way to refactor despite conflicts.
 */
class ApplyRefactoring<Q, T, D>(
    private val targets: RefactoringTargets<Q, T>,
    private val gateway: RefactoringGateway<T, D>,
    private val verb: String,
) : ActionHandler<RefactoringRequest<Q, D>, RefactoringOutcome<T>> {

    override suspend fun handle(request: RefactoringRequest<Q, D>): ToolResult<RefactoringOutcome<T>> =
        targets.locate(request.target).flatMap { target -> applyAnalysed(target, request.details, MAX_ATTEMPTS) }

    private suspend fun applyAnalysed(target: T, details: D, attemptsLeft: Int): ToolResult<RefactoringOutcome<T>> =
        gateway.analyse(target, details).flatMap { analysis ->
            if (analysis.conflicts.isNotEmpty()) {
                refused(target, analysis)
            } else {
                gateway.apply(analysis.plan).flatMap { attempt ->
                    when {
                        attempt is RefactoringAttempt.Applied -> ToolResult.success(
                            RefactoringOutcome.Applied(
                                target,
                                analysis.referenceCount,
                                attempt.changedFiles,
                                analysis.summary,
                            ),
                        )

                        attemptsLeft > 1 -> applyAnalysed(target, details, attemptsLeft - 1)

                        else -> keptChanging(target)
                    }
                }
            }
        }

    private fun keptChanging(target: T): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.CONFLICT,
        "The code kept changing while ${targets.nameOf(target)} was being $verb; nothing was changed.",
        "Retry when the project is not being edited.",
    )

    private fun refused(target: T, analysis: RefactoringAnalysis): ToolResult<Nothing> {
        val first = analysis.conflicts.sortedWith(RefactoringConflict.ORDER).first().message
        return ToolResult.failure(
            ErrorCode.CONFLICT,
            "${targets.nameOf(target)} was not $verb: ${analysis.conflicts.size} conflict(s), the first: \"$first\". " +
                "Nothing was changed.",
            "Run action=preview to list them, then change the code or the request so that none remain.",
        )
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
