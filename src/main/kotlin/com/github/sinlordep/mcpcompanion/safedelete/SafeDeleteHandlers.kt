package com.github.sinlordep.mcpcompanion.safedelete

import com.github.sinlordep.mcpcompanion.common.actions.ActionHandler
import com.github.sinlordep.mcpcompanion.common.actions.ActionRegistry
import com.github.sinlordep.mcpcompanion.common.paging.page
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocators

/** Use case of `mc_safe_delete`: one [ActionHandler] per [SafeDeleteAction]. */
class SafeDelete(locators: SymbolLocators, gateway: SafeDeleteGateway) {

    private val actions = ActionRegistry(
        mapOf(
            SafeDeleteAction.PREVIEW to PreviewSafeDelete(locators, gateway),
            SafeDeleteAction.APPLY to ApplySafeDelete(locators, gateway),
        ),
    )

    suspend fun execute(action: SafeDeleteAction, request: SafeDeleteRequest): ToolResult<SafeDeleteOutcome> =
        actions.dispatch(action, request)
}

/** `preview`: reports whether the symbol can be deleted safely, with one page of the blockers. Changes nothing. */
class PreviewSafeDelete(private val locators: SymbolLocators, private val gateway: SafeDeleteGateway) :
    ActionHandler<SafeDeleteRequest, SafeDeleteOutcome> {

    override suspend fun handle(request: SafeDeleteRequest): ToolResult<SafeDeleteOutcome> =
        locators.locate(request.symbol).flatMap { target ->
            gateway.analyse(target.handle).map { analysis ->
                SafeDeleteOutcome.Previewed(
                    target = target,
                    blockers = analysis.blockers.sortedWith(Blocker.ORDER).asSequence().page(request.page),
                    blockerCount = analysis.blockers.size,
                    cleanups = analysis.cleanups,
                    alsoDeleted = analysis.alsoDeleted,
                )
            }
        }
}

/**
 * `apply`: analyses again and deletes only when nothing speaks against it. If the code changes between the analysis
 * and the deletion, the analysis is repeated, a few times at most; there is no way to delete despite blockers.
 */
class ApplySafeDelete(private val locators: SymbolLocators, private val gateway: SafeDeleteGateway) :
    ActionHandler<SafeDeleteRequest, SafeDeleteOutcome> {

    override suspend fun handle(request: SafeDeleteRequest): ToolResult<SafeDeleteOutcome> =
        locators.locate(request.symbol).flatMap { target -> deleteAnalysed(target, MAX_ATTEMPTS) }

    private suspend fun deleteAnalysed(target: LocatedSymbol, attemptsLeft: Int): ToolResult<SafeDeleteOutcome> =
        gateway.analyse(target.handle).flatMap { analysis ->
            if (analysis.blockers.isNotEmpty()) {
                refused(target, analysis)
            } else {
                gateway.delete(analysis.plan).flatMap { attempt ->
                    when {
                        attempt == DeletionAttempt.Deleted ->
                            ToolResult.success(
                                SafeDeleteOutcome.Deleted(target, analysis.cleanups, analysis.alsoDeleted),
                            )

                        attemptsLeft > 1 -> deleteAnalysed(target, attemptsLeft - 1)

                        else -> keptChanging(target)
                    }
                }
            }
        }

    private fun keptChanging(target: LocatedSymbol): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.CONFLICT,
        "The code kept changing while ${target.name} was being deleted; nothing was deleted.",
        "Retry when the project is not being edited.",
    )

    private fun refused(target: LocatedSymbol, analysis: SafeDeleteAnalysis): ToolResult<Nothing> {
        val counts = analysis.blockers.groupingBy { it.kind }.eachCount()
            .entries.joinToString { (kind, count) -> "$count ${kind.name.lowercase()}" }
        return ToolResult.failure(
            ErrorCode.CONFLICT,
            "${target.name} cannot be deleted safely: ${analysis.blockers.size} blocker(s) ($counts). " +
                "Nothing was deleted.",
            "Run action=preview to list them, then remove or change that code first.",
        )
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
