package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.actions.ActionHandler
import com.github.sinlordep.mcpcompanion.common.actions.ActionRegistry
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import kotlinx.coroutines.sync.Mutex

/** One `mc_run` action. [parameters] are the arguments it accepts; any other argument is rejected before [handle]. */
interface RunActionHandler : ActionHandler<RunArguments, RunOutcome> {
    val action: RunAction
    val parameters: Set<RunParameter>
}

/** Use case of `mc_run`: dispatches each action to its handler (command pattern) after checking the arguments. */
class RunControl(handlers: List<RunActionHandler>) {

    private val actions = ActionRegistry(handlers.associate { it.action to ParameterGuard(it) })

    suspend fun execute(action: RunAction, arguments: RunArguments): ToolResult<RunOutcome> =
        actions.dispatch(action, arguments)

    companion object {
        /** Wires every action to the gateways. [startLock] is shared by all calls for one project. */
        fun create(
            catalog: ProcessCatalog,
            execution: ExecutionGateway,
            hotSwap: HotSwapGateway,
            ports: PortProbe,
            clock: Clock,
            startLock: Mutex,
        ): RunControl {
            val processes = ProcessResolver(catalog)
            val lifecycle = ProcessLifecycle(execution, catalog, clock, startLock)
            return RunControl(
                listOf(
                    ListProcesses(catalog),
                    StartProcess(ConfigurationResolver(execution), lifecycle),
                    StopProcess(processes, lifecycle),
                    RestartProcess(processes, lifecycle),
                    ProcessStatus(processes),
                    ReadOutput(processes),
                    WaitReady(processes, ports, clock),
                    HotSwapProcess(processes, hotSwap, lifecycle),
                ),
            )
        }
    }
}

/** Rejects arguments that do not apply to the action, so that a mistyped call fails instead of being half-obeyed. */
private class ParameterGuard(private val handler: RunActionHandler) : ActionHandler<RunArguments, RunOutcome> {

    override suspend fun handle(request: RunArguments): ToolResult<RunOutcome> {
        val unexpected = request.given() - handler.parameters
        if (unexpected.isEmpty()) return handler.handle(request)
        val name = handler.action.name.lowercase()
        val accepted = handler.parameters.joinToString { it.label }.ifEmpty { "none" }
        return ToolResult.failure(
            ErrorCode.INVALID_ARGUMENT,
            "action=$name does not take ${unexpected.joinToString { it.label }}.",
            "Parameters of action=$name: $accepted.",
        )
    }
}
