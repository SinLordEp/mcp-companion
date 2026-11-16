package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.run.output.ConsoleLine
import com.github.sinlordep.mcpcompanion.run.output.ConsoleSlice

/** Result of one `mc_run` action. */
sealed interface RunOutcome {

    data class Processes(val processes: List<ProcessSnapshot>) : RunOutcome

    /** `start` and `status`. */
    data class Process(val process: ProcessSnapshot) : RunOutcome

    data class Stopped(val process: ProcessSnapshot, val stop: StopResult) : RunOutcome

    /** [previous] is the process that was stopped, null when none was alive. */
    data class Restarted(val process: ProcessSnapshot, val previous: ProcessSnapshot?, val stop: StopResult?) :
        RunOutcome

    data class Output(val process: ProcessSnapshot, val slice: ConsoleSlice) : RunOutcome

    data class Ready(val process: ProcessSnapshot, val readiness: Readiness) : RunOutcome

    /** [restarted] is set when the hot swap failed and the caller asked to restart instead. */
    data class HotSwapped(val process: ProcessSnapshot, val report: HotSwapReport, val restarted: ProcessSnapshot?) :
        RunOutcome
}

/**
 * How a stop went. [terminated] is false when the process was still alive when the wait ended; [killed] when it was
 * killed after the graceful stop did not end it in time.
 */
data class StopResult(val request: StopRequest, val terminated: Boolean, val killed: Boolean, val waitedMillis: Long)

/** Which readiness condition ended `wait_ready`, with the matching line for output conditions. */
data class Readiness(
    val outcome: ReadinessOutcome,
    val condition: String,
    val line: ConsoleLine?,
    val waitedMillis: Long,
)

enum class ReadinessOutcome {
    /** A ready pattern matched or the port accepted a connection. */
    READY,

    /** A failure pattern matched. */
    FAILED,

    /** The process ended (or never started) before it became ready. */
    ENDED,

    /** None of the conditions held within the timeout. */
    TIMEOUT,
}
