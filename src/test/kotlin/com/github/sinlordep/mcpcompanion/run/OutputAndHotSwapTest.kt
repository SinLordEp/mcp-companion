package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.testing.ManualClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** `output` and `hotswap` over fake gateways. */
class OutputAndHotSwapTest {

    private val server = configuration("Server")
    private val catalog = FakeCatalog()
    private val execution = FakeExecution(catalog, server)
    private var report: ToolResult<HotSwapReport> = ToolResult.success(
        HotSwapReport.Reloaded("Server", listOf("a.B"), emptyList()),
    )
    private val hotSwapped = mutableListOf<ProcessId>()
    private val control = RunControl.create(
        catalog,
        execution,
        { process ->
            hotSwapped += process.id
            report
        },
        { false },
        ManualClock(),
        Mutex(),
    )

    private fun call(action: RunAction, arguments: RunArguments) = runBlocking { control.execute(action, arguments) }

    private fun process(executor: ExecutorKind = ExecutorKind.DEBUG) =
        FakeProcess(ProcessId.of(catalog.all.size + 1L), server, executor).also {
            catalog.all += it
        }

    @Test
    fun `output returns the tail by default and pages with since`() {
        val process = process()
        (1..60).forEach { process.print("line $it\n") }
        val tail = (call(RunAction.OUTPUT, RunArguments(process = "p1")).value() as RunOutcome.Output).slice
        assertEquals(50, tail.lines.size)
        assertEquals("line 60", tail.lines.last().text)
        assertEquals(60L, tail.nextCursor)
        val page = (
            call(
                RunAction.OUTPUT,
                RunArguments(process = "p1", since = 55, lines = 2),
            ).value() as RunOutcome.Output
            )
        assertEquals(listOf("line 56", "line 57"), page.slice.lines.map { it.text })
        assertEquals(57L, page.slice.nextCursor)
    }

    @Test
    fun `output filters with a regular expression and checks its arguments`() {
        val process = process()
        process.print("GET /a 200\nGET /b 500\nGET /c 500\n")
        val errors = call(
            RunAction.OUTPUT,
            RunArguments(configuration = "Server", pattern = " 5\\d\\d$"),
        ).value() as RunOutcome.Output
        assertEquals(listOf("GET /b 500", "GET /c 500"), errors.slice.lines.map { it.text })
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            call(RunAction.OUTPUT, RunArguments(process = "p1", lines = 501)).code(),
        )
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            call(RunAction.OUTPUT, RunArguments(process = "p1", since = 99)).code(),
        )
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            call(RunAction.OUTPUT, RunArguments(process = "p1", pattern = "[")).code(),
        )
        assertEquals(ErrorCode.INVALID_ARGUMENT, call(RunAction.OUTPUT, RunArguments(process = "p1", port = 1)).code())
    }

    @Test
    fun `hotswap reports what the session reloaded`() {
        process()
        val result = call(RunAction.HOTSWAP, RunArguments(process = "p1")).value() as RunOutcome.HotSwapped
        assertEquals(listOf("a.B"), (result.report as HotSwapReport.Reloaded).classes)
        assertNull(result.restarted)
        assertEquals(listOf(ProcessId("p1")), hotSwapped)
    }

    @Test
    fun `a run that is not a debug session is not hot-swapped but can be restarted instead`() {
        process(ExecutorKind.RUN)
        val refused = call(RunAction.HOTSWAP, RunArguments(process = "p1")).value() as RunOutcome.HotSwapped
        assertTrue(refused.report is HotSwapReport.NotPossible)
        assertTrue(hotSwapped.isEmpty())
        assertTrue(execution.started.isEmpty())

        val restarted = call(RunAction.HOTSWAP, RunArguments(process = "p1", restartOnFailure = true))
            .value() as RunOutcome.HotSwapped
        assertEquals("p2", restarted.restarted?.id?.value)
        assertEquals(listOf(server to ExecutorKind.RUN), execution.started)
    }

    @Test
    fun `a change the JVM rejects restarts only when asked`() {
        report =
            ToolResult.success(HotSwapReport.Rejected("Server", listOf("a.B"), listOf("add method not implemented")))
        process()
        val kept = call(RunAction.HOTSWAP, RunArguments(process = "p1")).value() as RunOutcome.HotSwapped
        assertNull(kept.restarted)
        assertTrue(execution.started.isEmpty())
        val restarted = call(RunAction.HOTSWAP, RunArguments(process = "p1", restartOnFailure = true))
            .value() as RunOutcome.HotSwapped
        assertEquals(listOf(server to ExecutorKind.DEBUG), execution.started)
        assertEquals("p1", restarted.process.id.value)
        assertEquals("p2", restarted.restarted?.id?.value)
    }

    @Test
    fun `compile errors never cause a restart`() {
        report = ToolResult.success(
            HotSwapReport.CompilationFailed("Server", listOf(CompilerError("';' expected", "src/A.java", 3, 9))),
        )
        process()
        val result = call(RunAction.HOTSWAP, RunArguments(process = "p1", restartOnFailure = true))
            .value() as RunOutcome.HotSwapped
        assertNull(result.restarted)
        assertTrue(execution.started.isEmpty())
    }

    @Test
    fun `only a running process can be hot-swapped`() {
        process().moveTo(ProcessState.Terminated(0))
        assertEquals(ErrorCode.CONFLICT, call(RunAction.HOTSWAP, RunArguments(process = "p1")).code())
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            call(RunAction.HOTSWAP, RunArguments(process = "p1", pattern = "x")).code(),
        )
    }
}
