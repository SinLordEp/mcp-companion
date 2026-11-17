package com.github.sinlordep.mcpcompanion.toolsets.run

import com.github.sinlordep.mcpcompanion.run.ide.ProcessTracker
import com.github.sinlordep.mcpcompanion.run.ide.TestResultsSupport
import com.github.sinlordep.mcpcompanion.testing.ScriptConfigurations
import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.github.sinlordep.mcpcompanion.testing.freshTracker
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.mcpserver.McpToolset
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking

/**
 * `mc_run tests` with the platform's test runner: a tracked process (a real one, started by `mc_run`) gets a test tree
 * built the way the IDE's test consoles build theirs, and its events are published on the IDE's own topic. Runs of
 * real test frameworks (JUnit, Gradle, Maven) are checked end to end (docs/notes-step3b.md).
 */
class RunTestsToolsetTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls
    private lateinit var tracker: ProcessTracker

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<RunToolset>().single()
    private val events get() = project.messageBus.syncPublisher(SMTRunnerEventsListener.TEST_STATUS)

    override fun setUp() {
        super.setUp()
        tracker = freshTracker(project, testRootDisposable)
        tools = ToolCalls(project)
        ScriptConfigurations(project, testRootDisposable).add("ticker", ScriptConfigurations.TICKER)
    }

    override fun tearDown() {
        try {
            runBlocking { tracker.processes() }.mapNotNull { tracker.handlerOf(it) }.forEach {
                it.destroyProcess()
                it.waitFor(WAIT_MILLIS)
            }
            tools.dispose()
        } finally {
            super.tearDown()
        }
    }

    private fun run(action: RunActionInput, process: String? = null, filter: TestFilterInput? = null): RunOutput =
        tools.call(TOOL) { toolset.run(action, process, filter = filter) }

    /** Starts the ticker through `mc_run` and returns its handle and process handler once it runs. */
    private fun started(): Pair<String, ProcessHandler> {
        val id = tools.call(TOOL) { toolset.run(RunActionInput.START, configuration = "ticker") }.process!!.id
        val ready = tools.call(TOOL) { toolset.run(RunActionInput.WAIT_READY, id, pattern = "READY") }
        assertEquals("ready", ready.readiness?.outcome)
        val handler = runBlocking { tracker.processes() }.single { it.id.value == id }.let { tracker.handlerOf(it)!! }
        return id to handler
    }

    /**
     * A tree like JUnit's: suite CalculatorTest with a passing, a compared failing and an ignored test, one that runs,
     * one not started yet and one the run was stopped in.
     */
    private fun tree(handler: ProcessHandler?): Triple<SMTestProxy.SMRootTestProxy, SMTestProxy, SMTestProxy> {
        val root = SMTestProxy.SMRootTestProxy()
        handler?.let(root::setHandler)
        val suite = SMTestProxy("CalculatorTest", true, "java:suite://pkg.CalculatorTest")
        root.addChild(suite)
        val passing = SMTestProxy("adds", false, "java:test://pkg.CalculatorTest/adds")
        val failing = SMTestProxy("greets", false, "java:test://pkg.CalculatorTest/greets")
        val ignored = SMTestProxy("subtracts", false, "java:test://pkg.CalculatorTest/subtracts")
        val running = SMTestProxy("divides", false, "java:test://pkg.CalculatorTest/divides")
        val notRun = SMTestProxy("multiplies", false, "java:test://pkg.CalculatorTest/multiplies")
        val stopped = SMTestProxy("rounds", false, "java:test://pkg.CalculatorTest/rounds")
        listOf(passing, failing, ignored, running, notRun, stopped).forEach(suite::addChild)
        root.setStarted()
        suite.setSuiteStarted()
        passing.setStarted()
        passing.setFinished()
        failing.setStarted()
        // Parameter order of the platform: message, stack trace, actual, expected.
        failing.setTestComparisonFailed(
            "org.junit.ComparisonFailure: ",
            "org.junit.ComparisonFailure\n\tat x",
            "Hello, World",
            "Hello, world",
        )
        ignored.setTestIgnored("not ready", null)
        running.setStarted()
        stopped.setStarted()
        stopped.setTerminated()
        return Triple(root, passing, failing)
    }

    fun testResultsOfARunInProgressAndAfterItsEnd() {
        assertTrue("the test runner support is loaded", TestResultsSupport.EP.extensionList.isNotEmpty())
        val (id, handler) = started()
        val (root, passing, failing) = tree(handler)
        events.onTestingStarted(root)
        events.onTestFinished(passing)
        events.onTestFailed(failing)

        val running = run(RunActionInput.TESTS, id).tests!!
        assertEquals("running", running.verdict)
        assertEquals(true, running.partial)
        assertEquals(
            "the outcomes through the proxies' public predicates",
            TestCountsOutput(total = 6, passed = 1, failed = 1, ignored = 1, running = 1, notRun = 1, interrupted = 1),
            running.counts,
        )
        val failed = running.items!!.single()
        assertEquals("greets", failed.name)
        assertEquals(listOf("CalculatorTest"), failed.suite)
        assertEquals("the platform's comparison: left is expected", "Hello, world", failed.failure?.expected)
        assertEquals("Hello, World", failed.failure?.actual)
        assertEquals(
            "no source in the light project: the URL instead",
            "java:test://pkg.CalculatorTest/greets",
            failed.url,
        )

        val summary = run(RunActionInput.PROCESSES).processes!!.single().tests!!
        assertEquals("running", summary.verdict)

        root.setFinished()
        events.onTestingFinished(root)
        val ended = run(RunActionInput.TESTS, id).tests!!
        assertEquals("failed", ended.verdict)
        assertNull(ended.partial)
        val ignored = run(RunActionInput.TESTS, id, TestFilterInput.IGNORED).tests!!.items!!.single()
        assertEquals("subtracts", ignored.name)
        // The platform keeps the reason of an ignored test as console output of the test, not as its error message.
        assertNull(ignored.failure)
        assertEquals("failed", run(RunActionInput.STATUS, id).process?.tests?.verdict)
    }

    fun testATreeWithoutHandlerOrOfAnotherProcessIsNotBound() {
        val (id, _) = started()
        val (unbound, passing, _) = tree(null)
        events.onTestingStarted(unbound)
        events.onTestFinished(passing)
        val foreign = tree(
            handler = object : ProcessHandler() {
                override fun destroyProcessImpl() = Unit

                override fun detachProcessImpl() = Unit

                override fun detachIsDefault() = false

                override fun getProcessInput() = null
            },
        ).first
        events.onTestingStarted(foreign)
        val answer = run(RunActionInput.TESTS, id).tests!!
        assertEquals("no_results", answer.verdict)
        assertTrue(answer.reason.orEmpty(), "not a test run" in answer.reason.orEmpty())
        assertNull(run(RunActionInput.STATUS, id).process?.tests)
    }

    fun testWithoutTheTestRunnerSupportTestsAreUnsupported() {
        val (id, _) = started()
        ExtensionTestUtil.maskExtensions(TestResultsSupport.EP, emptyList(), testRootDisposable)
        assertEquals("UNSUPPORTED", tools.errorCode(TOOL) { toolset.run(RunActionInput.TESTS, id) })
    }

    private companion object {
        const val TOOL = "mc_run"
        const val WAIT_MILLIS = 10_000L
    }
}
