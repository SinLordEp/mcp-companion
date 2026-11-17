package com.github.sinlordep.mcpcompanion.run.tests

import com.github.sinlordep.mcpcompanion.run.TrackedProcess

/** The compact test summary `processes` and `status` show for test runs, so that a client knows `tests` has more. */
class TestSummaries(private val tests: TestResultsGateway) {

    /** Null when the IDE reported no test results for [process] (or cannot report any). */
    fun of(process: TrackedProcess): TestSummary? =
        tests.resultsOf(process)?.read()?.run?.let { TestSummary.of(it, process.snapshot.state.hasEnded) }
}
