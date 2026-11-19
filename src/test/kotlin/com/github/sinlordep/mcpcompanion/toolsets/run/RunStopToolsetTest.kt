package com.github.sinlordep.mcpcompanion.toolsets.run

import com.github.sinlordep.mcpcompanion.run.ide.ProcessTracker
import com.github.sinlordep.mcpcompanion.testing.ScriptConfigurations
import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.github.sinlordep.mcpcompanion.testing.freshTracker
import com.github.sinlordep.mcpcompanion.testing.launchConfirmation
import com.intellij.execution.KillableProcess
import com.intellij.mcpserver.McpToolset
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking

/**
 * `mc_run stop` of a real process that ignores SIGINT (started as `sh -c 'trap "" INT; exec java ...'`, as a process
 * whose parent ignores the signal), which the IDE's graceful stop does not end. Unix only.
 */
class RunStopToolsetTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls
    private lateinit var scripts: ScriptConfigurations

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<RunToolset>().single()

    override fun setUp() {
        super.setUp()
        freshTracker(project, testRootDisposable)
        launchConfirmation(asks = false, testRootDisposable)
        tools = ToolCalls(project)
        scripts = ScriptConfigurations(project, testRootDisposable)
    }

    override fun tearDown() {
        try {
            val tracker = ProcessTracker.getInstance(project)
            runBlocking { tracker.processes() }.mapNotNull { tracker.handlerOf(it) }.forEach {
                if (it is KillableProcess && it.canKillProcess()) it.killProcess() else it.destroyProcess()
                it.waitFor(WAIT_MILLIS)
            }
            scripts.removeAll()
            tools.dispose()
        } finally {
            super.tearDown()
        }
    }

    private fun run(
        action: RunActionInput,
        process: String? = null,
        configuration: String? = null,
        pattern: String? = null,
        timeoutSeconds: Int? = null,
        force: Boolean? = null,
    ): RunOutput = tools.call(TOOL) {
        toolset.run(
            action,
            process,
            configuration,
            pattern = pattern,
            timeoutSeconds = timeoutSeconds,
            force = force,
        )
    }

    private fun startStubborn(): String {
        scripts.add("stubborn", ScriptConfigurations.TICKER, ignoresInterrupt = true)
        val id = run(RunActionInput.START, configuration = "stubborn").process!!.id
        assertEquals("ready", run(RunActionInput.WAIT_READY, id, pattern = "READY").readiness?.outcome)
        return id
    }

    fun testAProcessThatIgnoresTheGracefulStopIsKilledAfterTheGracePeriod() {
        if (!SystemInfo.isUnix) return
        val id = startStubborn()

        val stopped = run(RunActionInput.STOP, id, timeoutSeconds = 1)
        assertEquals("stopping", stopped.stop?.request)
        assertEquals("killed", stopped.stop?.outcome)
        assertEquals(true, stopped.stop?.terminated)
        assertEquals(true, stopped.stop?.killed)
        assertNull(stopped.stop?.reason)
        assertTrue("waited the grace period: ${stopped.stop}", stopped.stop!!.waitedMs >= GRACE_MILLIS)
        assertEquals(ProcessOutput.StateOutput.TERMINATED, stopped.process?.state)
    }

    fun testForceKillsAtOnce() {
        if (!SystemInfo.isUnix) return
        val id = startStubborn()

        val stopped = run(RunActionInput.STOP, id, timeoutSeconds = 60, force = true)
        assertEquals("killing", stopped.stop?.request)
        assertEquals("killed", stopped.stop?.outcome)
        assertTrue("no grace period: ${stopped.stop}", stopped.stop!!.waitedMs < FORCE_MAX_MILLIS)
    }

    fun testRestartKillsTheOldProcessAndStartsANewOne() {
        if (!SystemInfo.isUnix) return
        val id = startStubborn()

        val restarted = run(RunActionInput.RESTART, id, timeoutSeconds = 1)
        assertEquals("killed", restarted.stop?.outcome)
        assertEquals(id, restarted.previous?.id)
        assertEquals(ProcessOutput.StateOutput.TERMINATED, restarted.previous?.state)
        assertFalse(id == restarted.process?.id)
    }

    private companion object {
        const val TOOL = "mc_run"
        const val WAIT_MILLIS = 10_000L
        const val GRACE_MILLIS = 1_000L
        const val FORCE_MAX_MILLIS = 10_000L
    }
}
