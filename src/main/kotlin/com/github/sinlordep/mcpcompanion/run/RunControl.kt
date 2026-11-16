package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.actions.ActionRegistry
import com.github.sinlordep.mcpcompanion.common.actions.ParameterGuard
import com.github.sinlordep.mcpcompanion.common.actions.ParameterizedAction
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.time.Clock
import kotlinx.coroutines.sync.Mutex

/** One `mc_run` action. [parameters] are the arguments it accepts; any other argument is rejected before [handle]. */
interface RunActionHandler : ParameterizedAction<RunParameter, RunArguments, RunOutcome> {
    val action: RunAction

    override val actionName: String get() = action.name.lowercase()
}

/** Use case of `mc_run`: dispatches each action to its handler (command pattern) after checking the arguments. */
class RunControl(handlers: List<RunActionHandler>) {

    private val actions = ActionRegistry(handlers.associate { it.action to ParameterGuard(it, RunParameter::label) })

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
