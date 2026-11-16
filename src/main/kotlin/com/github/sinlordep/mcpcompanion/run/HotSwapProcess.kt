package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import kotlin.time.Duration.Companion.seconds

/**
 * `hotswap`: compiles changed classes and reloads them into the process's debug session without a restart. When the
 * reload is not possible and the caller asked for it (`restartOnFailure`), restarts the process instead. A build with
 * errors never restarts: the restart would fail on the same errors.
 */
class HotSwapProcess(
    private val processes: ProcessResolver,
    private val hotSwap: HotSwapGateway,
    private val lifecycle: ProcessLifecycle,
) : RunActionHandler {

    override val action = RunAction.HOTSWAP
    override val parameters = setOf(
        RunParameter.PROCESS,
        RunParameter.CONFIGURATION,
        RunParameter.TYPE,
        RunParameter.RESTART_ON_FAILURE,
    )

    override suspend fun handle(request: RunArguments): ToolResult<RunOutcome> =
        processes.resolve(request, aliveOnly = true).flatMap { process ->
            if (process.snapshot.state != ProcessState.Running) {
                notRunning(process)
            } else {
                report(process).flatMap { report ->
                    if (report.restartHelps && request.restartOnFailure == true) {
                        restartInstead(process, report)
                    } else {
                        ToolResult.success(RunOutcome.HotSwapped(process.snapshot, report, null))
                    }
                }
            }
        }

    private suspend fun report(process: TrackedProcess): ToolResult<HotSwapReport> {
        val snapshot = process.snapshot
        if (snapshot.executor == ExecutorKind.DEBUG) return hotSwap.hotSwap(process)
        return ToolResult.success(
            HotSwapReport.NotPossible(
                snapshot.name,
                "${process.id} was started with executor '${snapshot.executorId}', not debug; only debug sessions " +
                    "can reload classes. Start it with executor=debug, or pass restartOnFailure=true.",
            ),
        )
    }

    private suspend fun restartInstead(process: TrackedProcess, report: HotSwapReport): ToolResult<RunOutcome> =
        lifecycle.restart(process, RESTART_STOP_TIMEOUT, force = false).map { restarted ->
            RunOutcome.HotSwapped(restarted.previous ?: process.snapshot, report, restarted.process)
        }

    private fun notRunning(process: TrackedProcess): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.CONFLICT,
        "${process.id} is ${describe(process.snapshot.state)}; only a running debug session can be hot-swapped.",
        "Start the configuration with action=start executor=debug.",
    )

    private fun describe(state: ProcessState): String = when (state) {
        ProcessState.Starting -> "still starting"
        ProcessState.Terminating -> "stopping"
        is ProcessState.Terminated -> "terminated"
        is ProcessState.NotStarted -> "not started"
        ProcessState.Running -> "running"
    }

    private companion object {
        val RESTART_STOP_TIMEOUT = 10.seconds
    }
}
