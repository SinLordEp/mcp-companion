package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.time.Clock
import com.github.sinlordep.mcpcompanion.common.time.withTimeout
import kotlinx.coroutines.flow.first
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Stopping one process the way the IDE's Stop button does, with the kill on the clock instead of a second press. */
class ProcessStop(private val execution: ExecutionGateway, private val clock: Clock) {

    /**
     * What the IDE's Stop button does, pressed twice if needed: asks [process] to end (for a local process SIGINT to
     * its process tree, which a process that ignores the signal survives), waits up to [grace], then kills it when it
     * is still alive and its handler can be killed (SIGKILL to the tree). With [force] it is killed at once. An
     * attached process is detached and never killed. The caller refuses processes that are still starting.
     */
    suspend fun stop(process: TrackedProcess, grace: Duration, force: Boolean): StopResult {
        if (process.snapshot.state.hasEnded) return StopResult(StopRequest.NOT_RUNNING, StopOutcome.NOT_RUNNING, 0)
        val began = clock.now()
        val (request, outcome) = if (force) killAtOnce(process, grace) else stopGracefully(process, grace)
        return StopResult(request, outcome, clock.now().toEpochMilli() - began.toEpochMilli())
    }

    private suspend fun stopGracefully(process: TrackedProcess, grace: Duration): Pair<StopRequest, StopOutcome> {
        val request = execution.requestStop(process)
        if (awaitEnd(process, grace)) {
            val outcome = if (request == StopRequest.DETACHING) StopOutcome.DETACHED else StopOutcome.ENDED
            return request to outcome
        }
        if (request == StopRequest.DETACHING) return request to StopOutcome.STILL_ALIVE
        return request to kill(process)
    }

    /** Kills without asking first; a handler that cannot be killed gets the graceful stop instead. */
    private suspend fun killAtOnce(process: TrackedProcess, grace: Duration): Pair<StopRequest, StopOutcome> {
        if (!execution.kill(process)) return stopGracefully(process, grace)
        val outcome = if (awaitEnd(process, KILL_WAIT)) StopOutcome.KILLED else StopOutcome.ALIVE_AFTER_KILL
        return StopRequest.KILLING to outcome
    }

    private suspend fun kill(process: TrackedProcess): StopOutcome = when {
        !execution.kill(process) -> StopOutcome.NOT_KILLABLE
        awaitEnd(process, KILL_WAIT) -> StopOutcome.KILLED
        else -> StopOutcome.ALIVE_AFTER_KILL
    }

    private suspend fun awaitEnd(process: TrackedProcess, timeout: Duration): Boolean =
        clock.withTimeout(timeout) { process.snapshots.first { it.state.hasEnded } } != null

    private companion object {
        val KILL_WAIT: Duration = 5.seconds
    }
}
