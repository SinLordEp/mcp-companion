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

/** `tests` with results: listing, filters, pages, stack traces and verdicts, over fake gateways; no IDE. */
class TestResultsTest {

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
    fun `by default the failed tests are listed, with counts, verdict and failure details`() {
        results.publish(process.id, finishedRun)
        val report = results()
        assertEquals(TestVerdict.FAILED, report.summary.verdict)
        assertFalse(report.summary.partial)
        val counts = report.summary.counts
        assertEquals(listOf(6, 2, 3, 1), listOf(counts.total, counts.passed, counts.failed, counts.ignored))
        assertEquals(TestFilter.FAILED, report.filter)
        val items = report.items.items
        assertEquals(listOf("greets", "divides", "check"), items.map { it.case.name })
        assertEquals(SourcePosition("test/pkg/CalculatorTest.java", 17), items[0].position)
        assertEquals("Hello, world", items[0].failure?.expected)
        assertEquals("Hello, World", items[0].failure?.actual)
        assertEquals(
            "tests with the same name are told apart by their suite",
            listOf("NestedTest", "Large"),
            items[2].case.suite,
        )
        assertEquals("positions only for the failing tests of the page", listOf(1, 2, 5), results.located)
    }

    @Test
    fun `the filter selects passed, ignored or all tests, and passed tests carry no failure or position`() {
        results.publish(process.id, finishedRun)
        val passed = results(RunArguments(process = "p1", filter = TestFilter.PASSED)).items.items
        assertEquals(listOf("adds", "check"), passed.map { it.case.name })
        assertTrue(passed.all { it.failure == null && it.position == null })
        val ignored = results(RunArguments(process = "p1", filter = TestFilter.IGNORED)).items.items.single()
        assertEquals("not ready", ignored.failure?.message)
        assertTrue("no positions for passed and ignored tests", results.located.isEmpty())
        assertEquals(6, results(RunArguments(process = "p1", filter = TestFilter.ALL)).items.items.size)
        assertEquals(listOf(1, 2, 5), results.located)
    }

    @Test
    fun `pages follow the paging rules of common`() {
        results.publish(process.id, finishedRun)
        val first = results(RunArguments(process = "p1", filter = TestFilter.ALL, limit = 4))
        assertEquals(4, first.items.items.size)
        assertTrue(first.items.hasMore)
        assertEquals(4, first.items.nextOffset)
        val second = results(RunArguments(process = "p1", filter = TestFilter.ALL, limit = 4, offset = 4))
        assertEquals(listOf("check", "check"), second.items.items.map { it.case.name })
        assertFalse(second.items.hasMore)
        assertEquals(ErrorCode.INVALID_ARGUMENT, call(RunAction.TESTS, RunArguments(process = "p1", limit = 0)).code())
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            call(RunAction.TESTS, RunArguments(process = "p1", offset = -1)).code(),
        )
    }

    @Test
    fun `stack traces are cut to stackLines and marked truncated`() {
        val long = TestFailure("boom", (1..50).joinToString("\n") { "\tat pkg.Frame.m$it(Frame.java:$it)" })
        results.publish(process.id, TestRun(listOf(test("deep", TestOutcome.FAILED, failure = long)), true, null))
        val cut = results(RunArguments(process = "p1", stackLines = 3)).items.items.single().failure!!
        assertEquals(3, cut.stackTrace!!.lines().size)
        assertTrue(cut.truncated)
        val none = results(RunArguments(process = "p1", stackLines = 0)).items.items.single().failure!!
        assertNull(none.stackTrace)
        val all = results(RunArguments(process = "p1", stackLines = 200)).items.items.single().failure!!
        assertFalse(all.truncated)
        assertEquals(50, all.stackTrace!!.lines().size)
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            call(RunAction.TESTS, RunArguments(process = "p1", stackLines = 201)).code(),
        )
    }

    @Test
    fun `a run in progress is partial and running, with the failures so far`() {
        val running = TestRun(
            listOf(
                test("step1"),
                test("step2", TestOutcome.FAILED, failure = comparison),
                test("step3", TestOutcome.RUNNING),
            ),
            finished = false,
            durationMillis = null,
        )
        results.publish(process.id, running)
        val report = results()
        assertEquals(TestVerdict.RUNNING, report.summary.verdict)
        assertTrue(report.summary.partial)
        assertEquals(1, report.summary.counts.running)
        assertEquals(listOf("step2"), report.items.items.map { it.case.name })
    }

    @Test
    fun `a run that ended before its tests completed is interrupted, one without tests has none`() {
        val stopped = TestRun(listOf(test("step1"), test("step2", TestOutcome.INTERRUPTED)), false, null)
        results.publish(process.id, stopped)
        process.moveTo(ProcessState.Terminated(130))
        assertEquals(TestVerdict.INTERRUPTED, results().summary.verdict)
        assertTrue(results().summary.partial)

        results.publish(process.id, TestRun(emptyList(), finished = true, durationMillis = 0))
        assertEquals(TestVerdict.NO_TESTS, results().summary.verdict)
        results.publish(process.id, TestRun(listOf(test("adds"), test("subtracts", TestOutcome.IGNORED)), true, 5))
        assertEquals(TestVerdict.PASSED, results().summary.verdict)
    }
}
