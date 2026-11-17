package com.github.sinlordep.mcpcompanion.toolsets.run

import com.github.sinlordep.mcpcompanion.testing.ScriptConfiguration
import com.github.sinlordep.mcpcompanion.testing.ScriptConfigurations
import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.github.sinlordep.mcpcompanion.testing.freshTracker
import com.github.sinlordep.mcpcompanion.testing.launchConfirmation
import com.intellij.mcpserver.McpToolset
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * `mc_run` with the user's confirmation on (brave mode off), through the real gateways: a headless IDE has nobody to
 * ask, and the checks that need no user still come first. The dialog itself is checked end to end.
 */
class RunConsentToolsetTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls
    private lateinit var scripts: ScriptConfigurations

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<RunToolset>().single()

    override fun setUp() {
        super.setUp()
        freshTracker(project, testRootDisposable)
        launchConfirmation(asks = true, testRootDisposable)
        tools = ToolCalls(project)
        scripts = ScriptConfigurations(project, testRootDisposable)
    }

    override fun tearDown() {
        try {
            scripts.removeAll()
            tools.dispose()
        } finally {
            super.tearDown()
        }
    }

    private fun startError(name: String) =
        tools.errorCode(TOOL) { toolset.run(RunActionInput.START, configuration = name) }

    fun testAHeadlessIdeStartsNothingAndTheChecksComeFirst() {
        scripts.add("ticker", ScriptConfigurations.TICKER)
        val broken = scripts.add("broken", ScriptConfigurations.TICKER)
        (broken.configuration as ScriptConfiguration).source = null
        // The invalid configuration is refused by the checks before any question; the valid one reaches the question,
        // which nobody can answer in a headless IDE.
        assertEquals("CONFLICT", startError("broken"))
        assertEquals("UNSUPPORTED", startError("ticker"))
        assertEquals(0, tools.call(TOOL) { toolset.run(RunActionInput.PROCESSES) }.processes?.size)
    }

    private companion object {
        const val TOOL = "mc_run"
    }
}
