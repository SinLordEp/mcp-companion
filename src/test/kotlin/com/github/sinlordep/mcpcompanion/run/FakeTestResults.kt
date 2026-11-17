package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.run.tests.SourcePosition
import com.github.sinlordep.mcpcompanion.run.tests.TestCase
import com.github.sinlordep.mcpcompanion.run.tests.TestFailure
import com.github.sinlordep.mcpcompanion.run.tests.TestOutcome
import com.github.sinlordep.mcpcompanion.run.tests.TestResults
import com.github.sinlordep.mcpcompanion.run.tests.TestResultsGateway
import com.github.sinlordep.mcpcompanion.run.tests.TestRun
import com.github.sinlordep.mcpcompanion.run.tests.TestRunReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Test results in memory: [publish] plays the IDE reporting a test event. Positions are known for [positions]. */
class FakeTestResults(
    private val supported: ToolResult<Unit> = ToolResult.success(Unit),
    private val positions: Map<String, SourcePosition> = emptyMap(),
) : TestResultsGateway {
    private val runs = mutableMapOf<ProcessId, TestRun>()
    private val counters = mutableMapOf<ProcessId, MutableStateFlow<Long>>()

    /** Indices of `run.cases` whose positions were asked for. */
    val located = mutableListOf<Int>()

    fun publish(process: ProcessId, run: TestRun) {
        runs[process] = run
        counter(process).update { it + 1 }
    }

    override fun supported(): ToolResult<Unit> = supported

    override fun resultsOf(process: TrackedProcess): TestResults? = runs[process.id]?.let { initial ->
        object : TestResults {
            override val finished: Boolean get() = runs.getValue(process.id).finished

            override fun read(): TestRunReading = reading(runs[process.id] ?: initial)
        }
    }

    override fun events(process: TrackedProcess): StateFlow<Long> = counter(process.id)

    private fun counter(id: ProcessId) = counters.getOrPut(id) { MutableStateFlow(0L) }

    private fun reading(run: TestRun) = object : TestRunReading {
        override val run: TestRun = run

        override suspend fun positions(indices: List<Int>): List<SourcePosition?> {
            located += indices
            return indices.map { positions[run.cases[it].name] }
        }
    }
}

fun test(
    name: String,
    outcome: TestOutcome = TestOutcome.PASSED,
    suite: List<String> = listOf("CalculatorTest"),
    failure: TestFailure? = null,
) = TestCase(name, suite, "java:test://pkg.${suite.last()}/$name", outcome, durationMillis = 3, failure = failure)
