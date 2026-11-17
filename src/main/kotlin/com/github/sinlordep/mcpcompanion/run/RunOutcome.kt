package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.run.output.ConsoleLine
import com.github.sinlordep.mcpcompanion.run.output.ConsoleSlice
import com.github.sinlordep.mcpcompanion.run.tests.TestReport
import com.github.sinlordep.mcpcompanion.run.tests.TestSummary

/** Result of one `mc_run` action. */
sealed interface RunOutcome {

    /** [tests]: a test summary for each process that is a test run. */
    data class Processes(val processes: List<ProcessSnapshot>, val tests: Map<ProcessId, TestSummary> = emptyMap()) :
        RunOutcome

    /** `start` and `status`. [tests]: a test summary when the process is a test run (`status` only). */
    data class Process(val process: ProcessSnapshot, val tests: TestSummary? = null) : RunOutcome

    /** `tests`. */
    data class Tests(val process: ProcessSnapshot, val report: TestReport) : RunOutcome

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
