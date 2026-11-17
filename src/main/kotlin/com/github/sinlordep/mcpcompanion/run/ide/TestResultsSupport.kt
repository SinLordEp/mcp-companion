package com.github.sinlordep.mcpcompanion.run.ide

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.run.TrackedProcess
import com.github.sinlordep.mcpcompanion.run.tests.TestResults
import com.github.sinlordep.mcpcompanion.run.tests.TestResultsGateway
import com.github.sinlordep.mcpcompanion.run.tests.TestRunReading
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Test results for one family of test consoles. The implementation for the platform's test runner (the test trees of
 * JUnit, TestNG, Gradle and other "SM" consoles) is registered from `META-INF/mcp-companion-test-runner.xml`, so
 * without that plugin there is none and `tests` answers `UNSUPPORTED`.
 */
interface TestResultsSupport {

    /** Reports the test events of [project] to [sink] until [lifetime] ends. */
    fun observe(project: Project, sink: TestEventSink, lifetime: Disposable)

    companion object {
        val EP: ExtensionPointName<TestResultsSupport> =
            ExtensionPointName("com.github.sinlordep.mcpcompanion.testResultsSupport")
    }
}

/** Where a [TestResultsSupport] reports test events: the [ProcessTracker]. */
interface TestEventSink {
    /**
     * A test event of the run of [handler]. [tree] creates the reader of that run's test tree; it is called only for
     * the first event of a process, so the tracker owns exactly one tree per process.
     */
    fun testEvent(handler: ProcessHandler, phase: TestPhase, tree: () -> IdeTestTree)
}

/** Where a test run stands, as one event tells. */
enum class TestPhase { STARTED, PROGRESS, FINISHED }

/** The test tree the IDE builds for one process, read on demand. */
fun interface IdeTestTree {
    /** Reads the tree now; [finished] is whether the IDE reported the end of testing. */
    fun read(finished: Boolean): TestRunReading
}

/** The test state of one tracked process: its tree (from its first test event on) and its events. */
internal class ProcessTests {
    @Volatile
    var tree: IdeTestTree? = null
        private set

    @Volatile
    var finished: Boolean = false
        private set

    private val counter = MutableStateFlow(0L)
    val events: StateFlow<Long> get() = counter

    fun event(phase: TestPhase, newTree: () -> IdeTestTree) {
        synchronized(this) { if (tree == null) tree = newTree() }
        when (phase) {
            TestPhase.STARTED -> finished = false
            TestPhase.FINISHED -> finished = true
            TestPhase.PROGRESS -> Unit
        }
        counter.update { it + 1 }
    }

    /** Drops the tree, e.g. when the support that read it is unloaded. */
    fun forget() {
        synchronized(this) { tree = null }
        counter.update { it + 1 }
    }
}

/** [TestResultsGateway] over the [ProcessTracker]'s records and the registered [TestResultsSupport]s. */
internal class IdeTestResultsGateway(private val supports: () -> List<TestResultsSupport>) : TestResultsGateway {

    override fun supported(): ToolResult<Unit> = if (supports().isEmpty()) {
        ToolResult.failure(
            ErrorCode.UNSUPPORTED,
            "Test results need the IDE's test runner plugin (com.intellij.platform.testRunner), which this IDE does " +
                "not have.",
            "Read the test output with action=output instead.",
        )
    } else {
        ToolResult.success(Unit)
    }

    override fun resultsOf(process: TrackedProcess): TestResults? {
        val tests = (process as? IdeTrackedProcess)?.tests ?: return null
        val tree = tests.tree ?: return null
        return object : TestResults {
            override val finished: Boolean get() = tests.finished

            override fun read(): TestRunReading = tree.read(tests.finished)
        }
    }

    override fun events(process: TrackedProcess): StateFlow<Long> =
        (process as? IdeTrackedProcess)?.tests?.events ?: MutableStateFlow(0L)
}
