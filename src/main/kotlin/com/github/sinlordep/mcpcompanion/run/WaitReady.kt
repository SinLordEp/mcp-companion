package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.time.Clock
import com.github.sinlordep.mcpcompanion.common.time.timeoutOf
import com.github.sinlordep.mcpcompanion.common.time.withTimeout
import com.github.sinlordep.mcpcompanion.run.readiness.DefaultPatterns
import com.github.sinlordep.mcpcompanion.run.readiness.OutputCondition
import com.github.sinlordep.mcpcompanion.run.readiness.PortCondition
import com.github.sinlordep.mcpcompanion.run.readiness.ReadinessCondition
import com.github.sinlordep.mcpcompanion.run.readiness.ReadinessSignal
import com.github.sinlordep.mcpcompanion.run.readiness.TerminationCondition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `wait_ready`: suspends until the first readiness condition holds or the timeout passes. Conditions: ready and
 * failure patterns in the output (lines already captured count, from `since` on), the end of the process (always),
 * and optionally a port accepting connections.
 */
class WaitReady(private val processes: ProcessResolver, private val ports: PortProbe, private val clock: Clock) :
    RunActionHandler {

    override val action = RunAction.WAIT_READY
    override val parameters = setOf(
        RunParameter.PROCESS,
        RunParameter.CONFIGURATION,
        RunParameter.TYPE,
        RunParameter.PATTERN,
        RunParameter.FAIL_PATTERN,
        RunParameter.PORT,
        RunParameter.SINCE,
        RunParameter.TIMEOUT,
    )

    override suspend fun handle(request: RunArguments): ToolResult<RunOutcome> =
        timeoutOf(request.timeoutSeconds, DEFAULT_TIMEOUT, MAX_TIMEOUT).flatMap { timeout ->
            conditions(request).flatMap { output ->
                processes.resolve(request).flatMap { process -> await(process, output, request.port, timeout) }
            }
        }

    private fun conditions(request: RunArguments): ToolResult<OutputCondition> {
        val port = request.port
        val since = request.since ?: 0L
        if (port != null && port !in 1..MAX_PORT) {
            return ToolResult.failure(ErrorCode.INVALID_ARGUMENT, "port must be between 1 and $MAX_PORT.", null)
        }
        if (since < 0) return ToolResult.failure(ErrorCode.INVALID_ARGUMENT, "since must not be negative.", null)
        return RunPatterns.compile(request.pattern, RunParameter.PATTERN).flatMap { ready ->
            RunPatterns.compile(request.failPattern, RunParameter.FAIL_PATTERN).flatMap { failure ->
                val readyPatterns = ready?.let(::listOf) ?: if (port == null) DefaultPatterns.READY else emptyList()
                val failurePatterns = failure?.let(::listOf) ?: DefaultPatterns.FAILURE
                ToolResult.success(OutputCondition(readyPatterns, failurePatterns, since))
            }
        }
    }

    private suspend fun await(
        process: TrackedProcess,
        output: OutputCondition,
        port: Int?,
        timeout: Duration,
    ): ToolResult<RunOutcome> {
        val strategies: List<ReadinessCondition> =
            listOfNotNull(output, TerminationCondition, port?.let { PortCondition(it, ports, clock, PORT_INTERVAL) })
        val began = clock.now()
        val signal = clock.withTimeout(timeout) {
            merge(*strategies.map { it.signals(process) }.toTypedArray()).first()
        }
        val waited = clock.now().toEpochMilli() - began.toEpochMilli()
        val readiness = when (signal) {
            null -> Readiness(ReadinessOutcome.TIMEOUT, "timeout after ${timeout.inWholeSeconds} s", null, waited)
            is ReadinessSignal.ProcessEnded -> ended(process, output, signal.state, waited)
            else -> readinessOf(signal, waited)
        }
        return ToolResult.success(RunOutcome.Ready(process.snapshot, readiness))
    }

    /** The process ended; output it wrote before that still counts (it may have printed the ready line last). */
    private fun ended(process: TrackedProcess, output: OutputCondition, state: ProcessState, waited: Long): Readiness {
        val match = output.firstMatch(process.console)
        if (match != null) return readinessOf(match, waited)
        val condition = when (state) {
            is ProcessState.Terminated -> "process ended with exit code ${state.exitCode}"
            is ProcessState.NotStarted -> "process did not start" + state.reason?.let { ": $it" }.orEmpty()
            else -> "process ended"
        }
        return Readiness(ReadinessOutcome.ENDED, condition, null, waited)
    }

    private fun readinessOf(signal: ReadinessSignal, waited: Long): Readiness = when (signal) {
        is ReadinessSignal.OutputMatched -> Readiness(
            if (signal.failure) ReadinessOutcome.FAILED else ReadinessOutcome.READY,
            (if (signal.failure) "failPattern " else "pattern ") + signal.pattern,
            signal.line,
            waited,
        )

        is ReadinessSignal.PortOpen -> Readiness(ReadinessOutcome.READY, "port ${signal.port}", null, waited)

        is ReadinessSignal.ProcessEnded -> Readiness(ReadinessOutcome.ENDED, "process ended", null, waited)
    }

    private companion object {
        const val MAX_PORT = 65_535
        val DEFAULT_TIMEOUT: Duration = 60.seconds
        val MAX_TIMEOUT: Duration = 300.seconds
        val PORT_INTERVAL: Duration = 250.milliseconds
    }
}
