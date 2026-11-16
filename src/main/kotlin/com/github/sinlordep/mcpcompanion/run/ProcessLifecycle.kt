package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Starting, stopping and restarting, shared by `start`, `stop`, `restart` and `hotswap`. Holds the rules where the IDE
 * would ask the user: a configuration that does not allow parallel runs is never started a second time (the IDE asks
 * whether to stop the running one); `restart` stops exactly the given process and starts its configuration again.
 */
class ProcessLifecycle(
    private val execution: ExecutionGateway,
    private val catalog: ProcessCatalog,
    private val clock: Clock,
    private val startLock: Mutex,
) {

    /** Atomic per project: two concurrent starts of a configuration that does not allow parallel runs start one. */
    suspend fun start(configuration: ConfigurationRef, executor: ExecutorKind): ToolResult<TrackedProcess> =
        startLock.withLock { startUnlocked(configuration, executor) }

    private suspend fun startUnlocked(
        configuration: ConfigurationRef,
        executor: ExecutorKind,
    ): ToolResult<TrackedProcess> {
        val running = catalog.processes().filter {
            it.snapshot.configuration?.key == configuration.key && it.snapshot.state.isAlive
        }
        if (running.isNotEmpty() && !configuration.allowsParallelRuns) {
            return ToolResult.failure(
                ErrorCode.CONFLICT,
                "'${configuration.name}' is already running as ${running.joinToString { it.id.value }} and does not " +
                    "allow parallel runs (Allow multiple instances is off). Nothing was started.",
                "Use action=restart with that process, or action=stop first.",
            )
        }
        return execution.start(configuration, executor)
    }

    /**
     * What the IDE's Stop does, then waits up to [timeout] for the end; with [force], kills a process that is still
     * alive then.
     */
    suspend fun stop(process: TrackedProcess, timeout: Duration, force: Boolean): ToolResult<StopResult> {
        val state = process.snapshot.state
        if (state.hasEnded) return ToolResult.success(StopResult(StopRequest.NOT_RUNNING, true, false, 0))
        if (state == ProcessState.Starting) {
            return ToolResult.failure(
                ErrorCode.CONFLICT,
                "${process.id} is still starting (before-launch tasks such as Build run, or it is being launched); " +
                    "there is no process to stop yet.",
                "Call action=wait_ready or status and stop it once it runs.",
            )
        }
        val began = clock.now()
        val request = execution.requestStop(process)
        var ended = awaitEnd(process, timeout)
        var killed = false
        if (!ended && force && execution.kill(process)) {
            killed = true
            ended = awaitEnd(process, KILL_WAIT)
        }
        val waited = clock.now().toEpochMilli() - began.toEpochMilli()
        return ToolResult.success(StopResult(request, ended, killed, waited))
    }

    /** Stops [process] if it is alive, then starts its configuration again with the same executor. */
    suspend fun restart(process: TrackedProcess, timeout: Duration, force: Boolean): ToolResult<RunOutcome.Restarted> {
        val snapshot = process.snapshot
        val known = snapshot.configuration ?: return ToolResult.failure(
            ErrorCode.UNSUPPORTED,
            "${process.id} (${snapshot.name}) was not started from a run configuration this tool can find.",
            "Restart it from the IDE.",
        )
        if (snapshot.executor == ExecutorKind.OTHER) {
            return ToolResult.failure(
                ErrorCode.UNSUPPORTED,
                "${process.id} runs with executor '${snapshot.executorId}'; only run and debug can be started.",
                "Use action=stop, then action=start with executor run or debug.",
            )
        }
        val configuration = execution.configuration(known.key) ?: return ToolResult.failure(
            ErrorCode.NOT_FOUND,
            "The run configuration '${known.name}' of ${process.id} no longer exists.",
            "Start another configuration with action=start.",
        )
        if (snapshot.state.hasEnded) {
            return start(configuration, snapshot.executor).map { RunOutcome.Restarted(it.snapshot, null, null) }
        }
        return stop(process, timeout, force).flatMap { stop ->
            if (!stop.terminated) {
                stillRunning(process, timeout)
            } else {
                start(configuration, snapshot.executor).map {
                    RunOutcome.Restarted(it.snapshot, process.snapshot, stop)
                }
            }
        }
    }

    private suspend fun awaitEnd(process: TrackedProcess, timeout: Duration): Boolean =
        clock.withTimeout(timeout) { process.snapshots.first { it.state.hasEnded } } != null

    private fun stillRunning(process: TrackedProcess, timeout: Duration): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.CONFLICT,
        "${process.id} did not end within ${timeout.inWholeSeconds} s after the stop request; nothing was started.",
        "Retry with a longer timeoutSeconds, or with force=true to kill it.",
    )

    private companion object {
        val KILL_WAIT: Duration = 5.seconds
    }
}
