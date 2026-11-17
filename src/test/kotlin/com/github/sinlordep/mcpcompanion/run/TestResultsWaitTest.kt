package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.run.tests.SourcePosition
import com.github.sinlordep.mcpcompanion.run.tests.TestFailure
import com.github.sinlordep.mcpcompanion.run.tests.TestFilter
import com.github.sinlordep.mcpcompanion.run.tests.TestOutcome
import com.github.sinlordep.mcpcompanion.run.tests.TestReport
import com.github.sinlordep.mcpcompanion.run.tests.TestRun
import com.github.sinlordep.mcpcompanion.run.tests.TestVerdict
import com.github.sinlordep.mcpcompanion.testing.ManualClock
import com.github.sinlordep.mcpcompanion.testing.settleUntil
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * `tests` without results, its parameters and its waits, and the test summaries of `processes` and `status`, over fake
 * gateways; no IDE, no real time.
 */
class TestResultsWaitTest {

    private val process = FakeProcess(ProcessId("p1"), configuration("AllTests", typeId = "JUnit"))
    private val catalog = FakeCatalog(process)
    private val clock = ManualClock()
    private val results =
        FakeTestResults(positions = mapOf("greets" to SourcePosition("test/pkg/CalculatorTest.java", 17)))
    private val control = control(results)

    private fun control(tests: FakeTestResults) = RunControl.create(
        catalog,
        FakeExecution(catalog),
        { error("no hot swap") },
        tests,
        { false },
        FakeConsent(),
        clock,
        Mutex(),
    )

    private fun call(action: RunAction, arguments: RunArguments, on: RunControl = control) =
        runBlocking { on.execute(action, arguments) }

    private fun tests(arguments: RunArguments = RunArguments(process = "p1")) =
        (call(RunAction.TESTS, arguments).value() as RunOutcome.Tests).report

    private fun results(arguments: RunArguments = RunArguments(process = "p1")) = tests(arguments) as TestReport.Results

    private val comparison = TestFailure(
        message = "org.junit.ComparisonFailure: ",
        stackTrace = "org.junit.ComparisonFailure: expected:<[w]orld> but was:<[W]orld>\n\tat pkg.CalculatorTest" +
            ".greets(CalculatorTest.java:18)",
        expected = "Hello, world",
        actual = "Hello, World",
    )

    private val finishedRun = TestRun(
        cases = listOf(
            test("adds"),
            test("greets", TestOutcome.FAILED, failure = comparison),
            test(
                "divides",
                TestOutcome.FAILED,
                failure = TestFailure("java.lang.ArithmeticException: / by zero", "x"),
            ),
            test("subtracts", TestOutcome.IGNORED, failure = TestFailure("not ready", null)),
            test("check", suite = listOf("NestedTest", "Small")),
            test("check", TestOutcome.FAILED, listOf("NestedTest", "Large"), TestFailure("1000 is not below 100", "y")),
        ),
        finished = true,
        durationMillis = 120,
    )

    @Test
    fun `without results the reason depends on the process`() {
        val notStarted =
            FakeProcess(ProcessId("p2"), configuration("Broken"), state = ProcessState.NotStarted("Build failed"))
        val adopted = FakeProcess(ProcessId("p3"), configuration("Old"))
        catalog.all += listOf(notStarted, adopted)
        adopted.adopted()

        val starting = tests() as TestReport.Unavailable
        assertFalse(starting.notStarted)
        assertTrue(starting.reason, "have not started yet" in starting.reason)

        val failedStart = tests(RunArguments(process = "p2")) as TestReport.Unavailable
        assertTrue(failedStart.notStarted)
        assertTrue(failedStart.reason, "Build failed" in failedStart.reason && "build_project" in failedStart.reason)

        val old = tests(RunArguments(process = "p3")) as TestReport.Unavailable
        assertTrue(old.reason, "next test event" in old.reason)

        process.moveTo(ProcessState.Terminated(0))
        val ended = tests() as TestReport.Unavailable
        assertTrue(ended.reason, "not a test run" in ended.reason && "action=output" in ended.reason)
    }

    @Test
    fun `an IDE without a test runner answers UNSUPPORTED`() {
        val unsupported = FakeTestResults(ToolResult.failure(ErrorCode.UNSUPPORTED, "no test runner"))
        assertEquals(
            ErrorCode.UNSUPPORTED,
            call(RunAction.TESTS, RunArguments(process = "p1"), control(unsupported)).code(),
        )
        assertNull(
            "status works without one",
            (
                call(
                    RunAction.STATUS,
                    RunArguments(process = "p1"),
                    control(unsupported),
                ).value() as RunOutcome.Process
                ).tests,
        )
    }

    @Test
    fun `processes and status add a summary for test runs only`() {
        val other = FakeProcess(ProcessId("p2"), configuration("Server"))
        catalog.all += other
        results.publish(process.id, finishedRun)
        val listed = call(RunAction.PROCESSES, RunArguments()).value() as RunOutcome.Processes
        assertEquals(setOf(process.id), listed.tests.keys)
        assertEquals(TestVerdict.FAILED, listed.tests.getValue(process.id).verdict)
        val status = call(RunAction.STATUS, RunArguments(process = "p1")).value() as RunOutcome.Process
        assertEquals(3, status.tests?.counts?.failed)
        assertNull((call(RunAction.STATUS, RunArguments(process = "p2")).value() as RunOutcome.Process).tests)
    }

    @Test
    fun `tests rejects the parameters of other actions and other actions reject its parameters`() {
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            call(RunAction.TESTS, RunArguments(process = "p1", pattern = "x")).code(),
        )
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            call(RunAction.OUTPUT, RunArguments(process = "p1", filter = TestFilter.ALL)).code(),
        )
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            call(RunAction.TESTS, RunArguments(process = "p1", timeoutSeconds = 301)).code(),
        )
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            call(RunAction.TESTS, RunArguments(process = "p1", timeoutSeconds = -1)).code(),
        )
    }

    @Test
    fun `a wait ends with the end of testing that the IDE reports`() = runBlocking {
        val running = TestRun(listOf(test("step1", TestOutcome.RUNNING)), finished = false, durationMillis = null)
        results.publish(process.id, running)
        val wait = async { control.execute(RunAction.TESTS, RunArguments(process = "p1", timeoutSeconds = 60)) }
        settleUntil("the wait has started") { clock.sleeping == 1 }
        clock.advance(2.seconds)
        results.publish(process.id, running.copy(cases = listOf(test("step1")), finished = false))
        settleUntil("still waiting") { true }
        assertFalse(wait.isCompleted)
        results.publish(process.id, TestRun(listOf(test("step1")), finished = true, durationMillis = 2000))
        val report = (wait.await().value() as RunOutcome.Tests).report as TestReport.Results
        assertEquals(TestVerdict.PASSED, report.summary.verdict)
        assertEquals(false, report.wait?.timedOut)
        assertEquals(2000L, report.wait?.waitedMillis)
    }

    @Test
    fun `a wait times out on a run that goes on`() = runBlocking {
        results.publish(process.id, TestRun(listOf(test("step1", TestOutcome.RUNNING)), false, null))
        val wait = async { control.execute(RunAction.TESTS, RunArguments(process = "p1", timeoutSeconds = 5)) }
        settleUntil("the wait has started") { clock.sleeping == 1 }
        clock.advance(5.seconds)
        val report = (wait.await().value() as RunOutcome.Tests).report as TestReport.Results
        assertEquals(true, report.wait?.timedOut)
        assertEquals(TestVerdict.RUNNING, report.summary.verdict)
    }

    @Test
    fun `a wait on a process that ends gives its last test events a short grace`() = runBlocking {
        results.publish(process.id, TestRun(listOf(test("step1", TestOutcome.RUNNING)), false, null))
        val wait = async { control.execute(RunAction.TESTS, RunArguments(process = "p1", timeoutSeconds = 60)) }
        settleUntil("the wait has started") { clock.sleeping == 1 }
        process.moveTo(ProcessState.Terminated(1))
        settleUntil("the grace has started") { clock.sleeping == 2 }
        clock.advance(3.seconds)
        val report = (wait.await().value() as RunOutcome.Tests).report as TestReport.Results
        assertEquals(false, report.wait?.timedOut)
        assertEquals(TestVerdict.INTERRUPTED, report.summary.verdict)
    }

    @Test
    fun `a wait on a process without test results ends with the process and the grace`() = runBlocking {
        val wait = async { control.execute(RunAction.TESTS, RunArguments(process = "p1", timeoutSeconds = 60)) }
        settleUntil("the wait has started") { clock.sleeping == 1 }
        process.moveTo(ProcessState.Terminated(0))
        settleUntil("the grace has started") { clock.sleeping == 2 }
        clock.advance(3.seconds)
        val report = (wait.await().value() as RunOutcome.Tests).report
        assertTrue(report is TestReport.Unavailable)
        assertEquals(false, report.wait?.timedOut)
    }

    @Test
    fun `results that arrive after the process ended still end the wait as results`() = runBlocking {
        val wait = async { control.execute(RunAction.TESTS, RunArguments(process = "p1", timeoutSeconds = 60)) }
        settleUntil("the wait has started") { clock.sleeping == 1 }
        process.moveTo(ProcessState.Terminated(1))
        settleUntil("the grace has started") { clock.sleeping == 2 }
        results.publish(process.id, TestRun(emptyList(), finished = true, durationMillis = null))
        val report = (wait.await().value() as RunOutcome.Tests).report as TestReport.Results
        assertEquals(TestVerdict.NO_TESTS, report.summary.verdict)
        assertTrue(report.hint.orEmpty(), "action=output" in report.hint.orEmpty())
        assertEquals(0L, report.wait?.waitedMillis)
    }
}
