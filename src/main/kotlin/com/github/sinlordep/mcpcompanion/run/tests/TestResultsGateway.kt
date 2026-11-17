package com.github.sinlordep.mcpcompanion.run.tests

import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.run.TrackedProcess
import kotlinx.coroutines.flow.StateFlow

/** Gateway boundary: the test results the IDE collects for tracked processes. */
interface TestResultsGateway {
    /** A failure (UNSUPPORTED) when this IDE cannot report test results at all. */
    fun supported(): ToolResult<Unit>

    /** The results of [process]; null until the IDE reported a test event for it (it may not be a test run). */
    fun resultsOf(process: TrackedProcess): TestResults?

    /** Increases with every test event of [process] (conflated); waiting re-reads [resultsOf] on each change. */
    fun events(process: TrackedProcess): StateFlow<Long>
}

/** The live test results of one process. */
interface TestResults {
    /** Whether the IDE reported the end of testing; cheap. */
    val finished: Boolean

    /** The results as the IDE has them now. */
    fun read(): TestRunReading
}

/** One reading of the results: [run], and the declarations of its tests on demand. */
interface TestRunReading {
    val run: TestRun

    /**
     * Where the tests at [indices] of `run.cases` are declared, where the IDE can resolve it now (null otherwise, e.g.
     * while it indexes). Meant for the tests of one page only.
     */
    suspend fun positions(indices: List<Int>): List<SourcePosition?>
}
