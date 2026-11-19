package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.testing.ManualClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The user's confirmation of `start_at`: asked as for `start`, naming the code; a refusal leaves no configuration. */
class StartAtConsentTest {

    private val catalog = FakeCatalog()
    private val execution = FakeExecution(catalog)
    private val consent = FakeConsent(required = true)
    private val codeRuns = FakeCodeRuns(execution)
    private val control = RunControl.create(
        catalog,
        execution,
        { error("no hot swap") },
        FakeTestResults(),
        { false },
        consent,
        codeRuns,
        ManualClock(),
        Mutex(),
    )

    private fun call(action: RunAction, arguments: RunArguments) = runBlocking { control.execute(action, arguments) }

    @Test
    fun `a start from code asks like start and names the location and the temporary configuration`() {
        val test = configuration("CalcTest.adds", typeId = "JUnit", typeName = "JUnit")
        codeRuns.candidates = listOf(codeConfiguration(test))
        val started = call(RunAction.START_AT, RunArguments(path = "src/CalcTest.java", line = 12))
        assertTrue(started is ToolResult.Success)
        assertEquals(
            listOf(
                LaunchRequest(
                    test,
                    ExecutorKind.RUN,
                    LaunchPurpose.START,
                    origin = LaunchOrigin("src/CalcTest.java:12", temporary = true),
                ),
            ),
            consent.questions,
        )
        assertTrue(codeRuns.unregistered.isEmpty())
    }

    @Test
    fun `a denied start from code removes the temporary configuration it made, but never an existing one`() {
        consent.answer = ConsentAnswer.Denied
        val test = configuration("CalcTest", typeId = "JUnit", typeName = "JUnit")
        codeRuns.candidates = listOf(codeConfiguration(test))
        assertEquals(ErrorCode.DENIED, call(RunAction.START_AT, RunArguments(qualifiedName = "app.CalcTest")).code())
        assertEquals(listOf(test), codeRuns.unregistered)
        assertTrue(execution.started.isEmpty())

        codeRuns.unregistered.clear()
        execution.configurations += test
        codeRuns.candidates = listOf(codeConfiguration(test, existing = true))
        assertEquals(ErrorCode.DENIED, call(RunAction.START_AT, RunArguments(qualifiedName = "app.CalcTest")).code())
        assertTrue(codeRuns.unregistered.isEmpty())
        assertEquals(LaunchOrigin("app.CalcTest", temporary = false), consent.questions.last().origin)
    }
}
