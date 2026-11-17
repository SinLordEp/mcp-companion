package com.github.sinlordep.mcpcompanion.run.tests

/** Tests per outcome. [total] counts every test the tree knows, also those not run yet. */
data class TestCounts(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val ignored: Int,
    val running: Int,
    val notRun: Int,
    val interrupted: Int,
) {
    companion object {
        fun of(cases: List<TestCase>): TestCounts {
            val by = cases.groupingBy { it.outcome }.eachCount()
            fun count(outcome: TestOutcome) = by[outcome] ?: 0
            return TestCounts(
                total = cases.size,
                passed = count(TestOutcome.PASSED),
                failed = count(TestOutcome.FAILED),
                ignored = count(TestOutcome.IGNORED),
                running = count(TestOutcome.RUNNING),
                notRun = count(TestOutcome.NOT_RUN),
                interrupted = count(TestOutcome.INTERRUPTED),
            )
        }
    }
}

/** The overall result of a test run. */
enum class TestVerdict {
    /** The run goes on; failures so far are already counted. */
    RUNNING,

    /** Every test that ran passed (ignored ones allowed). */
    PASSED,

    /** At least one test failed. */
    FAILED,

    /** The run ended without any test, e.g. none matched, or the test JVM did not come up. */
    NO_TESTS,

    /** The run ended before all its tests completed (stopped, or the process died), without a failure. */
    INTERRUPTED,
}

/** Counts and verdict of a run. [partial]: the IDE has not reported the end of testing (yet). */
data class TestSummary(
    val verdict: TestVerdict,
    val counts: TestCounts,
    val partial: Boolean,
    val durationMillis: Long?,
) {
    companion object {
        /** The summary of [run] of a process that [processEnded] or not. */
        fun of(run: TestRun, processEnded: Boolean): TestSummary {
            val counts = TestCounts.of(run.cases)
            val unfinished = counts.running + counts.notRun + counts.interrupted
            val verdict = when {
                !run.finished && !processEnded -> TestVerdict.RUNNING
                counts.failed > 0 -> TestVerdict.FAILED
                counts.total == 0 -> TestVerdict.NO_TESTS
                !run.finished || unfinished > 0 -> TestVerdict.INTERRUPTED
                else -> TestVerdict.PASSED
            }
            return TestSummary(verdict, counts, partial = !run.finished, durationMillis = run.durationMillis)
        }
    }
}
