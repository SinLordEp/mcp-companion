package com.github.sinlordep.mcpcompanion.toolsets.run

import com.github.sinlordep.mcpcompanion.run.tests.BoundedFailure
import com.github.sinlordep.mcpcompanion.run.tests.ReportedTest
import com.github.sinlordep.mcpcompanion.run.tests.TestCounts
import com.github.sinlordep.mcpcompanion.run.tests.TestOutcome
import com.github.sinlordep.mcpcompanion.run.tests.TestReport
import com.github.sinlordep.mcpcompanion.run.tests.TestSummary
import kotlinx.serialization.Serializable

/**
 * Result of `tests`. verdict: running, passed, failed, no_tests, interrupted; or, without results (see reason),
 * not_started or no_results. partial: the IDE has not reported the end of testing. items: one page of the tests the
 * filter lets through; continue with offset=nextOffset.
 */
@Serializable
data class TestsOutput(
    val verdict: String,
    val partial: Boolean? = null,
    val counts: TestCountsOutput? = null,
    val durationMs: Long? = null,
    /** Why there are no results (or no tests), and what to do. */
    val reason: String? = null,
    /** A failure of the run as a whole, not of one test. */
    val problem: FailureOutput? = null,
    val filter: String? = null,
    val items: List<TestOutput>? = null,
    val offset: Int? = null,
    val hasMore: Boolean? = null,
    val nextOffset: Int? = null,
    val waitedMs: Long? = null,
    /** True: the run had not finished when timeoutSeconds passed. */
    val timedOut: Boolean? = null,
) {
    companion object {
        fun from(report: TestReport): TestsOutput {
            val wait = report.wait
            return when (report) {
                is TestReport.Unavailable -> TestsOutput(
                    verdict = if (report.notStarted) "not_started" else "no_results",
                    reason = report.reason,
                    waitedMs = wait?.waitedMillis,
                    timedOut = wait?.timedOut?.takeIf { it },
                )

                is TestReport.Results -> TestsOutput(
                    verdict = report.summary.verdict.name.lowercase(),
                    partial = report.summary.partial.takeIf { it },
                    counts = TestCountsOutput.from(report.summary.counts),
                    durationMs = report.summary.durationMillis,
                    reason = report.hint,
                    problem = report.problem?.let(FailureOutput::from),
                    filter = report.filter.name.lowercase(),
                    items = report.items.items.map(TestOutput::from),
                    offset = report.items.offset,
                    hasMore = report.items.hasMore,
                    nextOffset = report.items.nextOffset,
                    waitedMs = wait?.waitedMillis,
                    timedOut = wait?.timedOut?.takeIf { it },
                )
            }
        }
    }
}

/** The compact test summary of `processes` and `status`; `tests` has the details. */
@Serializable
data class TestSummaryOutput(val verdict: String, val partial: Boolean? = null, val counts: TestCountsOutput) {
    companion object {
        fun from(summary: TestSummary) = TestSummaryOutput(
            verdict = summary.verdict.name.lowercase(),
            partial = summary.partial.takeIf { it },
            counts = TestCountsOutput.from(summary.counts),
        )
    }
}

/** Tests per outcome; counts that are zero are left out, except total, passed and failed. */
@Serializable
data class TestCountsOutput(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val ignored: Int? = null,
    val running: Int? = null,
    val notRun: Int? = null,
    val interrupted: Int? = null,
) {
    companion object {
        fun from(counts: TestCounts) = TestCountsOutput(
            total = counts.total,
            passed = counts.passed,
            failed = counts.failed,
            ignored = counts.ignored.positive(),
            running = counts.running.positive(),
            notRun = counts.notRun.positive(),
            interrupted = counts.interrupted.positive(),
        )

        private fun Int.positive(): Int? = takeIf { it > 0 }
    }
}

/**
 * One test. outcome: passed, failed, ignored, running, not_run, interrupted. suite: enclosing suites from
 * the outside in. path+line: where the test is declared (failed tests, when the IDE resolves it), else url.
 */
@Serializable
data class TestOutput(
    val name: String,
    val suite: List<String>? = null,
    val outcome: String,
    val durationMs: Long? = null,
    val path: String? = null,
    val line: Int? = null,
    val url: String? = null,
    val failure: FailureOutput? = null,
) {
    companion object {
        fun from(test: ReportedTest): TestOutput {
            val case = test.case
            val failing = case.outcome == TestOutcome.FAILED
            return TestOutput(
                name = case.name,
                suite = case.suite.takeIf { it.isNotEmpty() },
                outcome = case.outcome.name.lowercase(),
                durationMs = case.durationMillis,
                path = test.position?.path,
                line = test.position?.line,
                url = case.locationUrl?.takeIf { failing && test.position == null },
                failure = test.failure?.let(FailureOutput::from),
            )
        }
    }
}

/**
 * Why a test did not pass. expected/actual when the IDE compared values. truncated: a text was shortened (the stack
 * trace to stackLines lines, other texts to 4,000 characters).
 */
@Serializable
data class FailureOutput(
    val message: String? = null,
    val expected: String? = null,
    val actual: String? = null,
    val stackTrace: String? = null,
    val truncated: Boolean? = null,
) {
    companion object {
        fun from(failure: BoundedFailure) = FailureOutput(
            message = failure.message,
            expected = failure.expected,
            actual = failure.actual,
            stackTrace = failure.stackTrace,
            truncated = failure.truncated.takeIf { it },
        )
    }
}
