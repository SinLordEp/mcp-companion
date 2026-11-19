package com.github.sinlordep.mcpcompanion.toolsets.run

import com.github.sinlordep.mcpcompanion.run.ide.ProcessTracker
import com.github.sinlordep.mcpcompanion.testing.ScriptConfigurations
import com.github.sinlordep.mcpcompanion.testing.ScriptProducers
import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.github.sinlordep.mcpcompanion.testing.freshTracker
import com.github.sinlordep.mcpcompanion.testing.launchConfirmation
import com.intellij.execution.RunManager
import com.intellij.mcpserver.McpToolset
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * `mc_run start_at` through the real gateways: the IDE's `ConfigurationContext` and its producers (test-only ones
 * standing in for JUnit, Gradle and Application, see [ScriptProducers]), temporary configurations of the `RunManager`,
 * and the launch of `start`. The real producers are checked end to end (docs/notes-step9.md).
 */
class RunAtCodeToolsetTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls
    private lateinit var scripts: ScriptConfigurations

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<RunToolset>().single()
    private val runManager get() = RunManager.getInstance(project)

    override fun setUp() {
        super.setUp()
        freshTracker(project, testRootDisposable)
        launchConfirmation(asks = false, testRootDisposable)
        tools = ToolCalls(project)
        scripts =
            ScriptConfigurations(project, testRootDisposable, typeId = "mcpCompanionTestTick." + getTestName(false))
        ScriptProducers(scripts, ScriptConfigurations.TICKER, testRootDisposable)
        myFixture.addFileToProject("app/Main.tick", "first line\n    second line\n")
        myFixture.addFileToProject("app/twice.tick", "x\n")
        myFixture.addFileToProject("app/Plain.txt", "nothing to run\n")
        myFixture.addFileToProject("ticks/One.txt", "in a directory\n")
    }

    override fun tearDown() {
        try {
            val tracker = ProcessTracker.getInstance(project)
            // Through a tool call, which dispatches events: the first listing adopts running processes on the EDT.
            tools.call(TOOL) { tracker.processes() }.mapNotNull { tracker.handlerOf(it) }.forEach {
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
    private fun startAt(
        path: String? = null,
        line: Int? = null,
        column: Int? = null,
        qualifiedName: String? = null,
        type: String? = null,
        configuration: String? = null,
    ): RunOutput = tools.call(TOOL) {
        toolset.run(
            RunActionInput.START_AT,
            configuration = configuration,
            type = type,
            path = path,
            line = line,
            column = column,
            qualifiedName = qualifiedName,
        )
    }

    private fun startAtError(path: String?, line: Int? = null, configuration: String? = null): String =
        tools.errorCode(TOOL) {
            toolset.run(RunActionInput.START_AT, configuration = configuration, path = path, line = line)
        }

    private fun temporaryNames(): List<String> = runManager.allSettings.filter { it.isTemporary }.map { it.name }

    fun testRunsTheCodeAtALineAsATemporaryConfigurationAndTracksIt() {
        val selected = runManager.selectedConfiguration
        val started = startAt(path = "app/Main.tick", line = 2).process!!
        assertEquals("tick Main.tick", started.configuration)
        assertEquals(true, started.temporary)
        assertEquals(listOf("tick Main.tick"), temporaryNames())
        assertSame("the user's selection stays", selected, runManager.selectedConfiguration)

        val ready = tools.call(TOOL) {
            toolset.run(RunActionInput.WAIT_READY, process = started.id, pattern = "READY", timeoutSeconds = 60)
        }
        assertEquals("ready", ready.readiness!!.outcome)
        val output = tools.call(TOOL) { toolset.run(RunActionInput.OUTPUT, process = started.id) }
        assertTrue(output.output!!.lines.any { it.text == "hello" })
        val listed = tools.call(TOOL) { toolset.run(RunActionInput.PROCESSES) }.processes!!
        assertEquals(listOf(started.id), listed.map { it.id })
    }

    fun testTheSameCodeAgainReusesTheConfigurationAndKeepsTheParallelRunRule() {
        startAt(path = "app/Main.tick")
        assertEquals("CONFLICT", startAtError("app/Main.tick", line = 1))
        assertEquals("one configuration, reused", listOf("tick Main.tick"), temporaryNames())
    }

    fun testSeveralCandidatesAreReportedAndConfigurationPicksOne() {
        assertEquals("AMBIGUOUS", startAtError("app/twice.tick"))
        assertTrue(temporaryNames().isEmpty())
        val started = startAt(path = "app/twice.tick", configuration = "tick twice.tick (again)").process!!
        assertEquals("tick twice.tick (again)", started.configuration)
    }

    fun testADirectoryRunsWhatTheIdeRunsForIt() {
        assertEquals("ticks in ticks", startAt(path = "ticks").process!!.configuration)
    }

    fun testNothingToRunAndWrongLocationsAreRefusedWithoutConfigurations() {
        assertEquals("NOT_FOUND", startAtError("app/Plain.txt", line = 1))
        assertEquals("NOT_FOUND", startAtError("app/Missing.tick"))
        assertEquals("INVALID_ARGUMENT", startAtError("app/Main.tick", line = 99))
        assertEquals("INVALID_ARGUMENT", startAtError("ticks", line = 1))
        val outside = File.createTempFile("Outside", ".tick").apply {
            deleteOnExit()
            writeText("x\n")
        }
        assertEquals("INVALID_ARGUMENT", startAtError(outside.path))
        assertEquals(
            "NOT_FOUND",
            tools.errorCode(TOOL) {
                toolset.run(RunActionInput.START_AT, qualifiedName = "nope.Nope")
            },
        )
        assertTrue(temporaryNames().isEmpty())
        assertTrue(tools.call(TOOL) { toolset.run(RunActionInput.PROCESSES) }.processes!!.isEmpty())
    }

    fun testWithoutAnswerTheTemporaryConfigurationIsRemovedAgain() {
        launchConfirmation(asks = true, testRootDisposable)
        // Headless: nobody can be asked, so nothing starts, and the configuration made for the launch goes again.
        assertEquals("UNSUPPORTED", startAtError("app/Main.tick", line = 1))
        assertTrue(temporaryNames().isEmpty())
    }

    private companion object {
        const val TOOL = "mc_run"
        const val WAIT_MILLIS = 10_000L
    }
}
