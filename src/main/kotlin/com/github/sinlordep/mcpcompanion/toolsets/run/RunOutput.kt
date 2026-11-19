package com.github.sinlordep.mcpcompanion.toolsets.run

import com.github.sinlordep.mcpcompanion.run.ExecutorKind
import com.github.sinlordep.mcpcompanion.run.ProcessSnapshot
import com.github.sinlordep.mcpcompanion.run.ProcessState
import com.github.sinlordep.mcpcompanion.run.Readiness
import com.github.sinlordep.mcpcompanion.run.RunOutcome
import com.github.sinlordep.mcpcompanion.run.StopResult
import com.github.sinlordep.mcpcompanion.run.output.ConsoleLine
import com.github.sinlordep.mcpcompanion.run.output.ConsoleSlice
import com.github.sinlordep.mcpcompanion.run.output.ConsoleStream
import com.github.sinlordep.mcpcompanion.run.tests.TestSummary
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** MCP output model of `mc_run`: one field per kind of result; only those of the action are set. */
@Serializable
data class RunOutput(
    val processes: List<ProcessOutput>? = null,
    val process: ProcessOutput? = null,
    /** restart: the process that was stopped. hotswap with a restart: the process before it. */
    val previous: ProcessOutput? = null,
    val stop: StopOutput? = null,
    val output: ConsoleOutput? = null,
    val readiness: ReadinessOutput? = null,
    val hotswap: HotSwapOutput? = null,
    val tests: TestsOutput? = null,
) {
    companion object {
        fun from(outcome: RunOutcome): RunOutput = when (outcome) {
            is RunOutcome.Processes -> RunOutput(
                processes = outcome.processes.map { ProcessOutput.from(it, outcome.tests[it.id]) },
            )

            is RunOutcome.Process -> RunOutput(process = ProcessOutput.from(outcome.process, outcome.tests))

            is RunOutcome.Tests -> RunOutput(
                process = ProcessOutput.from(outcome.process),
                tests = TestsOutput.from(outcome.report),
            )

            is RunOutcome.Output -> RunOutput(
                process = ProcessOutput.from(outcome.process),
                output = ConsoleOutput.from(outcome.slice),
            )

            is RunOutcome.Ready -> RunOutput(
                process = ProcessOutput.from(outcome.process),
                readiness = ReadinessOutput.from(outcome.readiness),
            )

            is RunOutcome.Stopped -> RunOutput(
                process = ProcessOutput.from(outcome.process),
                stop = StopOutput.from(outcome.stop),
            )

            is RunOutcome.Restarted -> RunOutput(
                process = ProcessOutput.from(outcome.process),
                previous = outcome.previous?.let(ProcessOutput::from),
                stop = outcome.stop?.let(StopOutput::from),
            )

            is RunOutcome.HotSwapped -> hotSwapped(outcome)
        }

        /** With a restart, the process is the new one and previous the one that could not take the change. */
        private fun hotSwapped(outcome: RunOutcome.HotSwapped) = RunOutput(
            process = ProcessOutput.from(outcome.restarted ?: outcome.process),
            previous = outcome.restarted?.let { ProcessOutput.from(outcome.process) },
            hotswap = HotSwapOutput.from(outcome.report, restarted = outcome.restarted != null),
        )
    }
}

@Serializable
data class ProcessOutput(
    val id: String,
    val name: String,
    val state: StateOutput,
    /** run, debug, or the id of another executor. */
    val executor: String,
    val exitCode: Int? = null,
    /** not_started: why, when the IDE said. */
    val reason: String? = null,
    val configuration: String? = null,
    val type: String? = null,
    /** True when the configuration is one of the IDE's temporary ones (start_at made it); absent otherwise. */
    val temporary: Boolean? = null,
    val startedAt: String,
    val endedAt: String? = null,
    val pid: Long? = null,
    /** False: the process ran before tracking began; output before that is missing. */
    val outputComplete: Boolean? = null,
    /** A test run's counts and verdict (processes, status); action=tests lists the tests. */
    val tests: TestSummaryOutput? = null,
) {
    @Serializable
    enum class StateOutput {
        @SerialName("starting")
        STARTING,

        @SerialName("running")
        RUNNING,

        @SerialName("terminating")
        TERMINATING,

        @SerialName("terminated")
        TERMINATED,

        @SerialName("not_started")
        NOT_STARTED,
    }

    companion object {
        fun from(snapshot: ProcessSnapshot, tests: TestSummary? = null): ProcessOutput {
            val state = snapshot.state
            return ProcessOutput(
                id = snapshot.id.value,
                name = snapshot.name,
                state = stateOf(state),
                executor = when (snapshot.executor) {
                    ExecutorKind.RUN -> "run"
                    ExecutorKind.DEBUG -> "debug"
                    ExecutorKind.OTHER -> snapshot.executorId
                },
                exitCode = (state as? ProcessState.Terminated)?.exitCode,
                reason = (state as? ProcessState.NotStarted)?.reason,
                configuration = snapshot.configuration?.name,
                type = snapshot.configuration?.typeId,
                temporary = snapshot.configuration?.temporary?.takeIf { it },
                startedAt = snapshot.startedAt.toString(),
                endedAt = snapshot.endedAt?.toString(),
                pid = snapshot.pid,
                outputComplete = if (snapshot.outputComplete) null else false,
                tests = tests?.let(TestSummaryOutput::from),
            )
        }

        private fun stateOf(state: ProcessState): StateOutput = when (state) {
            ProcessState.Starting -> StateOutput.STARTING
            ProcessState.Running -> StateOutput.RUNNING
            ProcessState.Terminating -> StateOutput.TERMINATING
            is ProcessState.Terminated -> StateOutput.TERMINATED
            is ProcessState.NotStarted -> StateOutput.NOT_STARTED
        }
    }
}

/**
 * outcome: ended (after the graceful stop), killed (ignored it and was killed after the grace period, or force),
 * detached, not_running, not_killable, alive_after_kill or still_alive (the last three: terminated=false).
 */
@Serializable
data class StopOutput(
    /** stopping, detaching (attached processes), killing (force) or not_running. */
    val request: String,
    val outcome: String,
    val terminated: Boolean,
    val killed: Boolean,
    val waitedMs: Long,
    /** Why the process is still alive, when terminated is false. */
    val reason: String? = null,
) {
    companion object {
        fun from(stop: StopResult) = StopOutput(
            request = stop.request.name.lowercase(),
            outcome = stop.outcome.name.lowercase(),
            terminated = stop.terminated,
            killed = stop.killed,
            waitedMs = stop.waitedMillis,
            reason = stop.outcome.reason.takeUnless { stop.terminated },
        )
    }
}

/**
 * Console lines. Continue polling with since=nextCursor. firstLine..lastLine is the retained range; missed counts lines
 * after since that were dropped (bounded buffer).
 */
@Serializable
data class ConsoleOutput(
    val lines: List<LineOutput>,
    val nextCursor: Long,
    val firstLine: Long,
    val lastLine: Long,
    val hasMore: Boolean,
    val missed: Long? = null,
) {
    companion object {
        fun from(slice: ConsoleSlice) = ConsoleOutput(
            lines = slice.lines.map(LineOutput::from),
            nextCursor = slice.nextCursor,
            firstLine = slice.firstSeq,
            lastLine = slice.lastSeq,
            hasMore = slice.hasMore,
            missed = slice.missed.takeIf { it > 0 },
        )
    }
}

/** One line; stream is absent for stdout, "err" for stderr, "ide" for lines the IDE wrote. */
@Serializable
data class LineOutput(val n: Long, val text: String, val stream: String? = null) {
    companion object {
        fun from(line: ConsoleLine) = LineOutput(
            n = line.seq,
            text = line.text,
            stream = when (line.stream) {
                ConsoleStream.STDOUT -> null
                ConsoleStream.STDERR -> "err"
                ConsoleStream.SYSTEM -> "ide"
            },
        )
    }
}

@Serializable
data class ReadinessOutput(
    /** ready, failed, ended or timeout. */
    val outcome: String,
    val condition: String,
    val line: LineOutput? = null,
    val waitedMs: Long,
) {
    companion object {
        fun from(readiness: Readiness) = ReadinessOutput(
            outcome = readiness.outcome.name.lowercase(),
            condition = readiness.condition,
            line = readiness.line?.let(LineOutput::from),
            waitedMs = readiness.waitedMillis,
        )
    }
}
