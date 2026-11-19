package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolError
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.testing.ManualClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `start_at`: which configuration from code is started, and when nothing is. */
class StartAtCodeTest {

    private val catalog = FakeCatalog()
    private val execution = FakeExecution(catalog)
    private val codeRuns = FakeCodeRuns(execution)
    private val control = RunControl.create(
        catalog,
        execution,
        { error("no hot swap") },
        FakeTestResults(),
        { false },
        FakeConsent(),
        codeRuns,
        ManualClock(),
        Mutex(),
    )

    private val junit = configuration("CalcTest.adds", typeId = "JUnit", typeName = "JUnit")
    private val gradle = configuration("CalcTest.adds", typeId = "GradleRunConfiguration", typeName = "Gradle")

    private fun startAt(arguments: RunArguments) = runBlocking { control.execute(RunAction.START_AT, arguments) }

    @Test
    fun `the one configuration at a line starts as a temporary one and returns its handle`() {
        codeRuns.candidates = listOf(codeConfiguration(junit))
        val outcome = startAt(RunArguments(path = "src/CalcTest.java", line = 7, executor = ExecutorKind.DEBUG))
        val process = (outcome.value() as RunOutcome.Process).process
        assertEquals("p1", process.id.value)
        assertEquals(ProcessState.Starting, process.state)
        assertEquals(listOf(junit to ExecutorKind.DEBUG), execution.started)
        assertEquals(listOf(junit), codeRuns.registered)
        assertEquals(listOf(CodeLocation.InFile("src/CalcTest.java", 7, null)), codeRuns.locations)
    }

    @Test
    fun `an existing configuration for the code runs as it is`() {
        execution.configurations += junit
        codeRuns.candidates = listOf(codeConfiguration(junit, existing = true))
        assertTrue(startAt(RunArguments(qualifiedName = "app.CalcTest#adds")) is ToolResult.Success)
        assertTrue(codeRuns.registered.isEmpty())
        assertEquals(listOf(CodeLocation.ByQualifiedName("app.CalcTest#adds")), codeRuns.locations)
    }

    @Test
    fun `several candidates are listed instead of asking, and type picks one`() {
        codeRuns.candidates = listOf(codeConfiguration(junit), codeConfiguration(gradle))
        val ambiguous = startAt(RunArguments(path = "src/CalcTest.java", line = 7))
        assertEquals(ErrorCode.AMBIGUOUS, ambiguous.code())
        assertTrue(
            ambiguous.message(),
            "type JUnit" in ambiguous.message() && "GradleRunConfiguration" in ambiguous.message(),
        )
        assertTrue(execution.started.isEmpty())

        assertTrue(startAt(RunArguments(path = "src/CalcTest.java", line = 7, type = "gradle")) is ToolResult.Success)
        assertEquals(listOf(gradle to ExecutorKind.RUN), execution.started)
    }

    @Test
    fun `configuration picks among candidates of the same type, and a wrong pick lists them`() {
        val inPackage = configuration("All in app", typeId = "JUnit", typeName = "JUnit")
        val inDirectory = configuration("Tests in 'app'", typeId = "JUnit", typeName = "JUnit")
        codeRuns.candidates = listOf(codeConfiguration(inPackage), codeConfiguration(inDirectory))
        assertEquals(ErrorCode.AMBIGUOUS, startAt(RunArguments(path = "src/app", type = "JUnit")).code())
        val wrong = startAt(RunArguments(path = "src/app", configuration = "nope"))
        assertEquals(ErrorCode.NOT_FOUND, wrong.code())
        assertTrue(wrong.message(), "'All in app'" in wrong.message())
        assertTrue(startAt(RunArguments(path = "src/app", configuration = "Tests in 'app'")) is ToolResult.Success)
        assertEquals(listOf(inDirectory to ExecutorKind.RUN), execution.started)
    }

    @Test
    fun `nothing to run at the location is NOT_FOUND with a next step`() {
        val nothing = startAt(RunArguments(path = "src/Util.java", line = 3))
        assertEquals(ErrorCode.NOT_FOUND, nothing.code())
        assertTrue(nothing.message(), "src/Util.java:3" in nothing.message())
        assertTrue((nothing as ToolResult.Failure).error.nextStep!!.contains("get_run_configurations"))
    }

    @Test
    fun `a candidate the IDE would ask about is refused and nothing is registered`() {
        codeRuns.candidates = listOf(codeConfiguration(junit, refusal = "the IDE asks which subclass to run"))
        val refused = startAt(RunArguments(qualifiedName = "app.AbstractTest#adds"))
        assertEquals(ErrorCode.UNSUPPORTED, refused.code())
        assertTrue(refused.message(), "which subclass" in refused.message())
        assertTrue(codeRuns.registered.isEmpty() && execution.started.isEmpty())
    }

    @Test
    fun `a refused launch removes the temporary configuration again`() {
        execution.launchRefusal = ToolResult.Failure(
            ToolError(ErrorCode.CONFLICT, "not valid"),
        )
        codeRuns.candidates = listOf(codeConfiguration(junit))
        assertEquals(ErrorCode.CONFLICT, startAt(RunArguments(path = "src/CalcTest.java")).code())
        assertEquals(listOf(junit), codeRuns.unregistered)
    }

    @Test
    fun `the location is validated before the IDE is asked`() {
        val cases = listOf(
            RunArguments(),
            RunArguments(path = "src/A.java", qualifiedName = "a.A"),
            RunArguments(path = "src/A.java", column = 3),
            RunArguments(path = "src/A.java", line = 0),
            RunArguments(path = "src/A.java", line = 2, column = 0),
            RunArguments(qualifiedName = "a.A", executor = ExecutorKind.OTHER),
        )
        cases.forEach { assertEquals(it.toString(), ErrorCode.INVALID_ARGUMENT, startAt(it).code()) }
        assertTrue(codeRuns.locations.isEmpty())
    }

    @Test
    fun `start_at takes only its own parameters`() {
        val rejected = startAt(RunArguments(path = "src/A.java", process = "p1"))
        assertEquals(ErrorCode.INVALID_ARGUMENT, rejected.code())
        assertTrue(rejected.message(), "process" in rejected.message())
    }

    @Test
    fun `a failed location lookup is passed on`() {
        codeRuns.failure = ToolResult.Failure(
            ToolError(ErrorCode.NOT_FOUND, "File not found: x"),
        )
        assertEquals(ErrorCode.NOT_FOUND, startAt(RunArguments(path = "x")).code())
    }
}
