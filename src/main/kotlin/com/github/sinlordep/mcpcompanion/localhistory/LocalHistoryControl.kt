package com.github.sinlordep.mcpcompanion.localhistory

import com.github.sinlordep.mcpcompanion.common.actions.ActionRegistry
import com.github.sinlordep.mcpcompanion.common.actions.ParameterGuard
import com.github.sinlordep.mcpcompanion.common.actions.ParameterizedAction
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import java.time.ZoneId

/** One `mc_local_history` action; arguments outside [parameters] are rejected before [handle]. */
interface LocalHistoryActionHandler :
    ParameterizedAction<LocalHistoryParameter, LocalHistoryArguments, LocalHistoryOutcome> {
    val action: LocalHistoryAction

    override val actionName: String get() = action.name.lowercase()
}

/** What an `mc_local_history` action answers. */
sealed interface LocalHistoryOutcome {
    /** `label`: the label just put. */
    data class Labelled(val label: HistoryLabel) : LocalHistoryOutcome

    /** `labels`: the labels of this tool the IDE still keeps, newest first. */
    data class Labels(val labels: List<HistoryLabel>) : LocalHistoryOutcome

    /** `diff` (from the target's state to now) or `preview` (what `revert` would change: from now back to it). */
    data class Changes(val target: HistoryTarget, val direction: DiffDirection, val comparison: HistoryComparison) :
        LocalHistoryOutcome

    /** `revert`: [attempt] says what was done. */
    data class Reverted(val path: String, val target: HistoryTarget, val attempt: RevertAttempt) : LocalHistoryOutcome
}

/** Use case of `mc_local_history`: dispatches each action to its handler after checking the arguments. */
class LocalHistoryControl(gateway: LocalHistoryGateway, zone: ZoneId) {

    private val actions = ActionRegistry(
        listOf(
            PutLabel(gateway),
            ListLabels(gateway),
            ShowChanges(gateway, zone, DiffDirection.SINCE_THEN),
            ShowChanges(gateway, zone, DiffDirection.BACK_TO_THEN),
            RevertToTarget(gateway, zone),
        ).associate { it.action to ParameterGuard(it, LocalHistoryParameter::label) },
    )

    suspend fun execute(action: LocalHistoryAction, arguments: LocalHistoryArguments): ToolResult<LocalHistoryOutcome> =
        actions.dispatch(action, arguments)

    companion object {
        const val DEFAULT_MAX_LINES: Int = 400
        const val MAX_LINES: Int = 5_000
        const val MAX_LABEL_LENGTH: Int = 200

        /** Labels remembered per project; older ones are forgotten (the IDE keeps them in its history). */
        const val MAX_LABELS: Int = 100
    }
}
