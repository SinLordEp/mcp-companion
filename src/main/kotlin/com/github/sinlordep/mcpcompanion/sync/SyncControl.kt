package com.github.sinlordep.mcpcompanion.sync

import com.github.sinlordep.mcpcompanion.common.actions.ActionRegistry
import com.github.sinlordep.mcpcompanion.common.actions.ParameterGuard
import com.github.sinlordep.mcpcompanion.common.actions.ParameterizedAction
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.time.Clock

/** One `mc_sync` action; arguments it does not take are rejected before [handle]. */
interface SyncActionHandler : ParameterizedAction<SyncParameter, SyncArguments, SyncOutcome> {
    val action: SyncAction

    override val actionName: String get() = action.name.lowercase()
}

/** Use case of `mc_sync`: dispatches each action to its handler (command pattern) after checking the arguments. */
class SyncControl(handlers: List<SyncActionHandler>) {

    private val actions = ActionRegistry(handlers.associate { it.action to ParameterGuard(it, SyncParameter::label) })

    suspend fun execute(action: SyncAction, arguments: SyncArguments): ToolResult<SyncOutcome> =
        actions.dispatch(action, arguments)

    companion object {
        /** Wires both actions; [coordinator] is shared by all calls for one project. */
        fun create(environment: SyncEnvironment, coordinator: SyncCoordinator, clock: Clock): SyncControl {
            val selection = UnitSelection(environment)
            return SyncControl(
                listOf(ReportStatus(selection, coordinator), RunSync(environment, selection, coordinator, clock)),
            )
        }
    }
}
