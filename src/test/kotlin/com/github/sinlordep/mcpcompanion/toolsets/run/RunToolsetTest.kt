package com.github.sinlordep.mcpcompanion.toolsets.run

import com.github.sinlordep.mcpcompanion.run.ide.ProcessTracker
import com.github.sinlordep.mcpcompanion.testing.ScriptConfiguration
import com.github.sinlordep.mcpcompanion.testing.ScriptConfigurations
import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import kotlinx.coroutines.runBlocking

/**
 * `mc_run` against real processes: a single-file Java program started through the platform's own Run runner, in an
 * MCP call context. Headless tests cannot show whether the IDE would open a dialog (it answers them itself); that is
 * checked end to end (docs/notes-step3.md).
 */
class RunToolsetTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls
    private lateinit var scripts: ScriptConfigurations

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<RunToolset>().single()

    override fun setUp() {
        super.setUp()
        // The light project outlives a test; a fresh tracker keeps tests apart.
        project.replaceService(ProcessTracker::class.java, ProcessTracker(project), testRootDisposable)
        tools = ToolCalls(project)
        scripts = ScriptConfigurations(project, testRootDisposable)
    }

    override fun tearDown() {
        try {
            val tracker = ProcessTracker.getInstance(project)
            runBlocking { tracker.processes() }.mapNotNull { tracker.handlerOf(it) }.forEach {
                it.destroyProcess()
                it.waitFor(WAIT_MILLIS)
            }
            scripts.removeAll()
            tools.dispose()
        } finally {
            super.tearDown()
        }
    }

    @Suppress("LongParameterList")
    private fun run(
        action: RunActionInput,
        process: String? = null,
        configuration: String? = null,
        executor: ExecutorInput? = null,
        since: Long? = null,
        pattern: String? = null,
    ): RunOutput = tools.call(TOOL) {
        toolset.run(
            action,
            process,
            configuration,
            executor = executor,
            since = since,
            pattern = pattern,
            timeoutSeconds = null,
        )
    }

    private fun errorCode(action: RunActionInput, configuration: String, executor: ExecutorInput? = null) =
        tools.errorCode(TOOL) { toolset.run(action, configuration = configuration, executor = executor) }

    private fun startReady(name: String): String {
        val id = run(RunActionInput.START, configuration = name).process!!.id
        assertEquals("ready", run(RunActionInput.WAIT_READY, id, pattern = "READY").readiness?.outcome)
        return id
    }

    fun testStartWaitReadOutputAndStop() {
        scripts.add("ticker", ScriptConfigurations.TICKER)
        val started = run(RunActionInput.START, configuration = "ticker").process!!
        assertEquals("ticker", started.configuration)
        assertEquals("run", started.executor)

        assertEquals("ready", run(RunActionInput.WAIT_READY, started.id, pattern = "READY").readiness?.outcome)
        val output = run(RunActionInput.OUTPUT, started.id, since = 0).output!!
        val lines = output.lines.filter { it.stream != "ide" }.map { it.text to it.stream }
        assertEquals(listOf("hello" to null, "READY" to null), lines.filter { it.second == null })
        assertTrue(lines.toString(), "oops" to "err" in lines)
        assertNotNull(run(RunActionInput.STATUS, started.id).process?.pid)

        val stopped = run(RunActionInput.STOP, started.id)
        assertEquals(true, stopped.stop?.terminated)
        assertEquals(ProcessOutput.StateOutput.TERMINATED, stopped.process?.state)
        assertNotNull(stopped.process?.exitCode)
    }

    fun testASecondStartIsRefusedWhileItRuns() {
        scripts.add("ticker", ScriptConfigurations.TICKER)
        startReady("ticker")
        assertEquals("CONFLICT", errorCode(RunActionInput.START, "ticker"))
        assertEquals(1, run(RunActionInput.PROCESSES).processes?.size)
    }

    fun testParallelRunsAreStartedWhenTheConfigurationAllowsThem() {
        scripts.add("parallel", ScriptConfigurations.TICKER, parallel = true)
        val first = startReady("parallel")
        val second = startReady("parallel")
        assertNotSame(first, second)
        assertEquals(setOf(first, second), run(RunActionInput.PROCESSES).processes?.map { it.id }?.toSet())
    }

    fun testTheExitCodeEndsAWait() {
        scripts.add("crash", ScriptConfigurations.TICKER, "exit", "3")
        val id = run(RunActionInput.START, configuration = "crash").process!!.id
        val result = run(RunActionInput.WAIT_READY, id, pattern = "NEVER")
        assertEquals("ended", result.readiness?.outcome)
        assertEquals(3, result.process?.exitCode)
    }

    fun testAProcessTheUserStartsIsCapturedFromItsFirstLine() {
        val settings = scripts.add("by-user", ScriptConfigurations.TICKER)
        ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).buildAndExecute()
        val ready = run(RunActionInput.WAIT_READY, configuration = "by-user", pattern = "READY")
        assertEquals("ready", ready.readiness?.outcome)
        assertNull("tracked from the start", ready.process?.outputComplete)
        val texts = run(RunActionInput.OUTPUT, configuration = "by-user", since = 0).output!!.lines.map { it.text }
        assertTrue(texts.toString(), "hello" in texts)
    }

    fun testAnInvalidConfigurationIsRefusedAndNothingStarts() {
        val settings = scripts.add("broken", ScriptConfigurations.TICKER)
        (settings.configuration as ScriptConfiguration).source = null
        assertEquals("CONFLICT", errorCode(RunActionInput.START, "broken"))
        assertEquals(0, run(RunActionInput.PROCESSES).processes?.size)
    }

    fun testBeforeLaunchTasksThatMayAskAreRefused() {
        scripts.add("first", ScriptConfigurations.TICKER)
        val settings = scripts.add("second", ScriptConfigurations.TICKER)
        val provider = BeforeRunTaskProvider.EP_NAME.getExtensions(project).single {
            it.id.toString() ==
                "RunConfigurationTask"
        }
        settings.configuration.beforeRunTasks =
            listOf(provider.createTask(settings.configuration)!!.apply { isEnabled = true })
        val message = tools.call(TOOL) {
            try {
                toolset.run(RunActionInput.START, configuration = "second")
                "started"
            } catch (e: McpExpectedError) {
                e.message.orEmpty()
            }
        }
        assertTrue(message, "[UNSUPPORTED]" in message && "RunConfigurationTask" in message)
        assertEquals(0, run(RunActionInput.PROCESSES).processes?.size)
    }

    fun testSettingsThatWouldMakeTheIdeAskAreRefused() {
        val prompting = scripts.add(
            "prompting",
            ScriptConfigurations.TICKER,
            "${'$'}Prompt${'$'}",
            "${'$'}FilePrompt:Pick${'$'}",
        )
        val message = startMessage("prompting")
        assertTrue(
            message,
            "[UNSUPPORTED]" in message && "${'$'}Prompt${'$'}" in message && "${'$'}FilePrompt${'$'}" in message,
        )
        (prompting.configuration as ScriptConfiguration).arguments = listOf("${'$'}ProjectFileDir${'$'}")
        prompting.isEditBeforeRun = true
        assertTrue(startMessage("prompting").startsWith("[CONFLICT]"))
        assertEquals(0, run(RunActionInput.PROCESSES).processes?.size)
    }

    fun testAStartTheRunnerNeverHandsOverEndsAsNotStarted() {
        val settings = scripts.add("stateless", ScriptConfigurations.TICKER)
        (settings.configuration as ScriptConfiguration).withoutState = true
        val started = run(RunActionInput.START, configuration = "stateless").process!!
        assertEquals(ProcessOutput.StateOutput.NOT_STARTED, started.state)
        assertTrue(started.reason.orEmpty(), "did not start anything" in started.reason.orEmpty())
        (settings.configuration as ScriptConfiguration).withoutState = false
        assertEquals(
            "a later start is not blocked",
            "run",
            run(RunActionInput.START, configuration = "stateless").process?.executor,
        )
    }

    fun testEveryStartGetsItsOwnExecutionId() {
        scripts.add("ticker", ScriptConfigurations.TICKER, parallel = true)
        val ids = mutableListOf<Long>()
        project.messageBus.connect(testRootDisposable).subscribe(
            ExecutionManager.EXECUTION_TOPIC,
            object : ExecutionListener {
                override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
                    ids += env.executionId
                }
            },
        )
        startReady("ticker")
        startReady("ticker")
        assertEquals(ids.toString(), 2, ids.toSet().size)
        assertFalse(ids.toString(), 0L in ids)
    }

    private fun startMessage(configuration: String): String = tools.call(TOOL) {
        try {
            toolset.run(RunActionInput.START, configuration = configuration)
            "started"
        } catch (e: McpExpectedError) {
            e.message.orEmpty()
        }
    }

    fun testWithoutADebugRunnerDebugIsUnsupportedAndHotswapNotPossible() {
        scripts.add("ticker", ScriptConfigurations.TICKER)
        assertEquals("UNSUPPORTED", errorCode(RunActionInput.START, "ticker", ExecutorInput.DEBUG))
        val id = startReady("ticker")
        assertEquals("not_possible", run(RunActionInput.HOTSWAP, id).hotswap?.outcome)
    }

    fun testAnEndedProcessIsReleasedWhenItsRunTabCloses() {
        scripts.add("ticker", ScriptConfigurations.TICKER)
        val tabs = mutableListOf<RunContentDescriptor>()
        project.messageBus.connect(testRootDisposable).subscribe(
            ExecutionManager.EXECUTION_TOPIC,
            object : ExecutionListener {
                override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                    env.contentToReuse?.let(tabs::add)
                }
            },
        )
        val id = startReady("ticker")
        run(RunActionInput.STOP, id)
        assertEquals(
            "ended processes stay readable",
            "terminated",
            run(RunActionInput.STATUS, id).process?.state?.name?.lowercase(),
        )
        Disposer.dispose(tabs.single())
        assertEquals("NOT_FOUND", tools.errorCode(TOOL) { toolset.run(RunActionInput.STATUS, process = id) })
    }

    private companion object {
        const val TOOL = "mc_run"
        const val WAIT_MILLIS = 10_000L
    }
}
