package com.github.sinlordep.mcpcompanion.contract

import com.github.sinlordep.mcpcompanion.testing.ScriptConfigurations
import com.github.sinlordep.mcpcompanion.yield.reflectMcpToolNames
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.mcpserver.McpToolset
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Platform behaviour `mc_run` relies on (docs/notes-step3.md). If one of these fails after an IDE update, re-check the
 * process tracker (`run/ide`) before changing the expectation.
 */
class ExecutionContractTest : BasePlatformTestCase() {

    private class Recorder : ExecutionListener {
        val events = mutableListOf<String>()
        var scheduled: ExecutionEnvironment? = null
        var starting: ExecutionEnvironment? = null
        var startNotifiedWhenStarting: Boolean? = null
        var tabOfHandler: Boolean? = null
        var handler: ProcessHandler? = null

        override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
            events += "scheduled"
            scheduled = env
        }

        override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
            events += "starting"
            starting = env
            this.handler = handler
            startNotifiedWhenStarting = handler.isStartNotified
            tabOfHandler = env.contentToReuse?.processHandler === handler
        }

        override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
            events += "started"
        }
    }

    /**
     * The tracker attaches its output listener in `processStarting(…, handler)`: that must come before `startNotify()`
     * (or the first lines are lost), for the same environment object as the schedule event (our own starts are
     * registered by environment), with the run tab already holding the handler (its disposal releases the process).
     */
    fun testProcessStartingComesBeforeStartNotifyWithTheScheduledEnvironmentAndItsTab() {
        val recorder = Recorder()
        project.messageBus.connect(testRootDisposable).subscribe(ExecutionManager.EXECUTION_TOPIC, recorder)
        val settings = ScriptConfigurations(project, testRootDisposable).add("contract", ScriptConfigurations.TICKER)
        try {
            ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).buildAndExecute()
            PlatformTestUtil.waitWithEventsDispatching("no start", { "started" in recorder.events }, WAIT_SECONDS)
            assertEquals(listOf("scheduled", "starting", "started"), recorder.events)
            assertSame(recorder.scheduled, recorder.starting)
            assertEquals(false, recorder.startNotifiedWhenStarting)
            assertEquals(true, recorder.tabOfHandler)
        } finally {
            recorder.handler?.let {
                it.destroyProcess()
                it.waitFor(WAIT_SECONDS * 1_000L)
            }
        }
    }

    /**
     * The official run and debug tools this IDE serves, as analysed for `mc_run` (docs/notes-step3.md): none stops,
     * restarts or reads the output of a run process, and none hot-swaps. A new name here may make an action of
     * `mc_run` redundant: check it, and add a catalog entry if it replaces the whole tool.
     */
    fun testTheOfficialRunAndDebugToolsAreTheAnalysedOnes() {
        val related =
            Regex("run_configuration|process|debugger_session|control_session|terminal|monitor_|hot_?swap|reload")
        val names = McpToolset.EP.extensionList
            .filterNot { it.javaClass.name.startsWith("com.github.sinlordep.mcpcompanion") }
            .flatMap { reflectMcpToolNames(it.javaClass) }
            .filter { related.containsMatchIn(it) }
            .toSortedSet()
        assertEquals(ANALYSED_TOOLS, names)
    }

    /**
     * `LaunchChecks` lets only these before-launch tasks run, the Java plugin's build tasks that were checked to show
     * no UI. If an id changes, every configuration with a build step would be refused.
     */
    fun testTheCheckedBuildTasksStillExist() {
        val ids = BeforeRunTaskProvider.EP_NAME.getExtensions(project).map { it.id.toString() }
        assertContainsElements(ids, "Make", "MakeNoErrorCheck", "MakeProject")
    }

    private companion object {
        const val WAIT_SECONDS = 30

        val ANALYSED_TOOLS = sortedSetOf(
            "coverage_run_configuration",
            "execute_run_configuration",
            "execute_terminal_command",
            "get_run_configurations",
            "monitor_start",
            "monitor_stop",
            "monitor_wait",
            "xdebug_attach_to_process",
            "xdebug_control_session",
            "xdebug_get_process_output",
            "xdebug_start_debugger_session",
        )
    }
}
