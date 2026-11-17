package com.github.sinlordep.mcpcompanion.build

import com.github.sinlordep.mcpcompanion.common.actions.ActionRegistry
import com.github.sinlordep.mcpcompanion.common.actions.ParameterGuard
import com.github.sinlordep.mcpcompanion.common.actions.ParameterizedAction
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.time.Clock

/** One `mc_build` action; arguments it does not take are rejected before [handle]. */
interface BuildActionHandler : ParameterizedAction<BuildParameter, BuildArguments, BuildOutcome> {
    val action: BuildAction

    override val actionName: String get() = action.name.lowercase()
}

/** Use case of `mc_build`: dispatches each action to its handler (command pattern) after checking the arguments. */
class BuildControl(handlers: List<BuildActionHandler>) {

    private val actions =
        ActionRegistry(handlers.associate { it.action to ParameterGuard(it, BuildParameter::label) })

    suspend fun execute(action: BuildAction, arguments: BuildArguments): ToolResult<BuildOutcome> =
        actions.dispatch(action, arguments)

    companion object {
        /** Wires both actions; [coordinator] is shared by all calls for one project. */
        fun create(environment: BuildEnvironment, coordinator: BuildCoordinator, clock: Clock): BuildControl =
            BuildControl(listOf(BuildModule(environment, coordinator, clock), ReportBuildStatus(coordinator)))
    }
}

/** `status`: the running build and the latest one that ended (also one whose call timed out). */
class ReportBuildStatus(private val coordinator: BuildCoordinator) : BuildActionHandler {
    override val action = BuildAction.STATUS
    override val parameters = setOf(BuildParameter.INCLUDE_WARNINGS)

    override suspend fun handle(request: BuildArguments): ToolResult<BuildOutcome> = ToolResult.success(
        BuildOutcome.Status(coordinator.running, coordinator.latest, request.includeWarnings == true),
    )
}
