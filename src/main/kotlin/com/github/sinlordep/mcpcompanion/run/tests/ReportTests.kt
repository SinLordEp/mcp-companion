package com.github.sinlordep.mcpcompanion.run.tests

import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.paging.page
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.time.Clock
import com.github.sinlordep.mcpcompanion.common.time.withTimeout
import com.github.sinlordep.mcpcompanion.run.ProcessResolver
import com.github.sinlordep.mcpcompanion.run.ProcessSnapshot
import com.github.sinlordep.mcpcompanion.run.ProcessState
import com.github.sinlordep.mcpcompanion.run.RunAction
import com.github.sinlordep.mcpcompanion.run.RunActionHandler
import com.github.sinlordep.mcpcompanion.run.RunArguments
import com.github.sinlordep.mcpcompanion.run.RunOutcome
import com.github.sinlordep.mcpcompanion.run.RunParameter
import com.github.sinlordep.mcpcompanion.run.TrackedProcess
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `tests`: the test results of a process, while it runs (partial) and after it ended (as long as it is retained):
 * counts, verdict, and one page of the tests [TestFilter] lets through (failed ones by default) with their failure.
 * With `timeoutSeconds`, waits first until the IDE reports the end of testing, or the process ends.
 */
class ReportTests(
    private val processes: ProcessResolver,
    private val tests: TestResultsGateway,
    private val clock: Clock,
) : RunActionHandler {

    override val action = RunAction.TESTS
    override val parameters = setOf(
        RunParameter.PROCESS,
        RunParameter.CONFIGURATION,
        RunParameter.TYPE,
        RunParameter.FILTER,
        RunParameter.LIMIT,
        RunParameter.OFFSET,
        RunParameter.STACK_LINES,
        RunParameter.TIMEOUT,
    )

    private class Query(val filter: TestFilter, val page: PageRequest, val stackLines: Int, val wait: Duration)

    override suspend fun handle(request: RunArguments): ToolResult<RunOutcome> = query(request).flatMap { query ->
        tests.supported().flatMap {
            processes.resolve(request).flatMap { process -> ToolResult.success(report(process, query)) }
        }
    }

    private suspend fun report(process: TrackedProcess, query: Query): RunOutcome {
        val wait = if (query.wait.isPositive()) awaitEnd(process, query.wait) else null
        val snapshot = process.snapshot
        val results = tests.resultsOf(process) ?: return RunOutcome.Tests(snapshot, unavailable(snapshot, wait))
        val reading = results.read()
        val run = reading.run
        val page = run.cases.withIndex().filter { query.filter.accepts(it.value.outcome) }.asSequence()
            .page(query.page)
        val failing = page.items.filter { it.value.outcome.isFailure() }.map { it.index }
        val positions = failing.zip(reading.positions(failing)).toMap()
        val items = page.items.map { (index, case) ->
            ReportedTest(
                case = case,
                position = positions[index],
                failure = case.failure?.takeIf { case.outcome.isFailure() || case.outcome == TestOutcome.IGNORED }
                    ?.let { BoundedFailure.of(it, query.stackLines) },
            )
        }
        val summary = TestSummary.of(run, snapshot.state.hasEnded)
        val report = TestReport.Results(
            summary = summary,
            hint = NO_TESTS_HINT.takeIf { summary.verdict == TestVerdict.NO_TESTS },
            problem = run.problem?.let { BoundedFailure.of(it, query.stackLines) },
            filter = query.filter,
            items = Page(items, page.offset, page.hasMore, page.nextOffset),
            wait = wait,
        )
        return RunOutcome.Tests(snapshot, report)
    }

    /**
     * Event-driven: until the IDE reports the end of testing or the process ends. A process that ended before the IDE
     * reported the end of its testing gets [END_GRACE] for its last events: a Gradle test task's results (even its
     * first ones, when it failed to compile) arrive after its process ended (seen end to end).
     */
    private suspend fun awaitEnd(process: TrackedProcess, timeout: Duration): TestWait {
        val began = clock.now()
        val events = tests.events(process)
        fun finished() = tests.resultsOf(process)?.finished == true
        val done = clock.withTimeout(timeout) {
            combine(events, process.snapshots) { _, snapshot -> snapshot.state.hasEnded }
                .first { ended -> ended || finished() }
            if (!finished()) clock.withTimeout(END_GRACE) { events.first { finished() } }
        } != null
        val waited = clock.now().toEpochMilli() - began.toEpochMilli()
        return TestWait(waited, timedOut = !done)
    }

    private fun unavailable(snapshot: ProcessSnapshot, wait: TestWait?): TestReport.Unavailable {
        val id = snapshot.id
        val state = snapshot.state
        val reason = when {
            state is ProcessState.NotStarted ->
                "$id did not start" + state.reason?.let { ": $it" }.orEmpty() + ", so no test ran. Fix the cause " +
                    "(compile errors: the official build_project tool) and start it again."

            !snapshot.outputComplete && state.isAlive ->
                "$id was already running when MCP Companion began tracking it; its test results become available " +
                    "with its next test event."

            !snapshot.outputComplete ->
                "$id ran before MCP Companion began tracking it, so its test results are not known."

            state.isAlive ->
                "No test results for $id so far: it is not a test run, or its tests have not started yet (build " +
                    "tasks run first). Call again later, or pass timeoutSeconds to wait."

            else ->
                "The IDE reported no test results for $id: it is not a test run, it ended before its tests started " +
                    "(e.g. a build step failed), or the IDE shows its tests only as console output (e.g. a Maven " +
                    "run). Read the console with action=output."
        }
        return TestReport.Unavailable(state is ProcessState.NotStarted, reason, wait)
    }

    private fun query(request: RunArguments): ToolResult<Query> {
        val stackLines = request.stackLines ?: DEFAULT_STACK_LINES
        if (stackLines !in 0..MAX_STACK_LINES) {
            return ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "stackLines must be between 0 and $MAX_STACK_LINES, was $stackLines.",
                "Omit it for the first $DEFAULT_STACK_LINES lines of each stack trace.",
            )
        }
        return waitOf(request.timeoutSeconds).flatMap { wait ->
            PageRequest.of(request.limit, request.offset, DEFAULT_LIMIT).flatMap { page ->
                ToolResult.success(Query(request.filter ?: TestFilter.FAILED, page, stackLines, wait))
            }
        }
    }

    /** `timeoutSeconds`: absent or 0 means do not wait. */
    private fun waitOf(seconds: Int?): ToolResult<Duration> = when {
        seconds == null || seconds == 0 -> ToolResult.success(Duration.ZERO)

        seconds in 1..MAX_WAIT_SECONDS -> ToolResult.success(seconds.seconds)

        else -> ToolResult.failure(
            ErrorCode.INVALID_ARGUMENT,
            "timeoutSeconds must be between 0 and $MAX_WAIT_SECONDS for action=tests, was $seconds.",
            "Omit it (or pass 0) to answer at once; up to $MAX_WAIT_SECONDS waits for the end of the test run.",
        )
    }

    private fun TestOutcome.isFailure(): Boolean = this == TestOutcome.FAILED

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val DEFAULT_STACK_LINES = 10
        const val MAX_STACK_LINES = 200
        const val MAX_WAIT_SECONDS = 300
        val END_GRACE: Duration = 3.seconds
        const val NO_TESTS_HINT = "No test ran. The console (action=output) usually tells why, e.g. no test " +
            "matched or the test JVM did not start. A Gradle test task's console is not captured: if it failed to " +
            "compile, the official build_project tool lists the errors."
    }
}
