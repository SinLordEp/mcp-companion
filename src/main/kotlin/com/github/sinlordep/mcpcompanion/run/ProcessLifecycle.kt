package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolError
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/**
 * Starting, stopping and restarting, shared by `start`, `stop`, `restart` and `hotswap`. Holds the rules where the IDE
 * would ask the user: a configuration that does not allow parallel runs is never started a second time (the IDE asks
 * whether to stop the running one); `restart` stops exactly the given process and starts its configuration again.
 *
 * Every launch needs the user's permission ([LaunchApproval]), asked at most once per action and only after the
 * refusals that need no user (already running, [ExecutionGateway.checkLaunch]). A restart asks before it stops
 * anything.
 */
class ProcessLifecycle(
    private val execution: ExecutionGateway,
    private val catalog: ProcessCatalog,
    private val approval: LaunchApproval,
    private val clock: Clock,
    private val startLock: Mutex,
) {

    private val stopper = ProcessStop(execution, clock)

    /**
     * Atomic per project: two concurrent starts of a configuration that does not allow parallel runs start one. The
     * question is asked under the lock, so concurrent launches are asked one after the other and a later one sees the
     * process of an earlier one.
     */
    suspend fun start(
        configuration: ConfigurationRef,
        executor: ExecutorKind,
        origin: LaunchOrigin? = null,
    ): ToolResult<TrackedProcess> = launch(LaunchRequest(configuration, executor, LaunchPurpose.START, origin = origin))

    /** See [ProcessStop.stop]. */
    suspend fun stop(process: TrackedProcess, grace: Duration, force: Boolean): ToolResult<StopResult> {
        stillStarting(process)?.let { return it }
        return ToolResult.success(stopper.stop(process, grace, force))
    }

    /**
     * Stops [process] if it is alive, then starts its configuration again with the same executor. The user is asked
     * once, about [purpose], before anything is stopped.
     */
    suspend fun restart(
        process: TrackedProcess,
        grace: Duration,
        force: Boolean,
        purpose: LaunchPurpose = LaunchPurpose.RESTART,
    ): ToolResult<RunOutcome.Restarted> {
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
            return launch(LaunchRequest(configuration, snapshot.executor, purpose))
                .map { RunOutcome.Restarted(it.snapshot, null, null) }
        }
        stillStarting(process)?.let { return it }
        val request = LaunchRequest(configuration, snapshot.executor, purpose, stopsRunning = true)
        return startLock.withLock { permitted(request) }
            .flatMap { stop(process, grace, force) }
            .flatMap { stop ->
                if (!stop.terminated) {
                    stillRunning(process, stop)
                } else {
                    launchPermitted(request).map { RunOutcome.Restarted(it.snapshot, process.snapshot, stop) }
                }
            }
    }

    private suspend fun launch(request: LaunchRequest): ToolResult<TrackedProcess> = startLock.withLock {
        notRunning(request.configuration)
            .flatMap { permitted(request) }
            .flatMap { execution.start(request.configuration, request.executor) }
    }

    /** A launch the user already permitted: only the parallel-run rule is checked again. */
    private suspend fun launchPermitted(request: LaunchRequest): ToolResult<TrackedProcess> = startLock.withLock {
        notRunning(request.configuration).flatMap { execution.start(request.configuration, request.executor) }
    }

    /** The refusals that need no user first, then the user's permission. */
    private suspend fun permitted(request: LaunchRequest): ToolResult<Unit> =
        execution.checkLaunch(request.configuration, request.executor).flatMap { approval.approve(request) }

    private suspend fun notRunning(configuration: ConfigurationRef): ToolResult<Unit> {
        val running = catalog.processes().filter {
            it.snapshot.configuration?.key == configuration.key && it.snapshot.state.isAlive
        }
        if (running.isEmpty() || configuration.allowsParallelRuns) return ToolResult.success(Unit)
        return ToolResult.failure(
            ErrorCode.CONFLICT,
            "'${configuration.name}' is already running as ${running.joinToString { it.id.value }} and does not " +
                "allow parallel runs (Allow multiple instances is off). Nothing was started.",
            "Use action=restart with that process, or action=stop first.",
        )
    }

    private fun stillStarting(process: TrackedProcess): ToolResult.Failure? {
        if (process.snapshot.state != ProcessState.Starting) return null
        return ToolResult.Failure(
            ToolError(
                ErrorCode.CONFLICT,
                "${process.id} is still starting (before-launch tasks such as Build run, or it is being launched); " +
                    "there is no process to stop yet.",
                "Call action=wait_ready or status and stop it once it runs.",
            ),
        )
    }

    private fun stillRunning(process: TrackedProcess, stop: StopResult): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.CONFLICT,
        "${process.id} is still alive after ${stop.waitedMillis / MILLIS_PER_SECOND} s: ${stop.outcome.reason}. " +
            "Nothing was started.",
        "Stop it in the IDE or end it from outside, then retry.",
    )

    private companion object {
        const val MILLIS_PER_SECOND = 1000
    }
}
