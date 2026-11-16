package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** start, stop, restart, status and processes over fake gateways; no IDE, no real time. */
class RunLifecycleTest {

    private val server = configuration("Server")
    private val catalog = FakeCatalog()
    private val execution = FakeExecution(catalog, server)
    private val clock = ManualClock()
    private val control = RunControl.create(catalog, execution, { error("no hot swap") }, { false }, clock, Mutex())

    private fun call(action: RunAction, arguments: RunArguments) = runBlocking { control.execute(action, arguments) }

    private fun running(configuration: ConfigurationRef = server, executor: ExecutorKind = ExecutorKind.RUN) =
        FakeProcess(ProcessId.of(catalog.all.size + 1L), configuration, executor).also { catalog.all += it }

    @Test
    fun `start returns at once with a starting process`() {
        val outcome = call(RunAction.START, RunArguments(configuration = "Server", executor = ExecutorKind.DEBUG))
        val process = (outcome.value() as RunOutcome.Process).process
        assertEquals(ProcessState.Starting, process.state)
        assertEquals(listOf(server to ExecutorKind.DEBUG), execution.started)
        assertEquals("p1", process.id.value)
    }

    @Test
    fun `start refuses a second run unless the configuration allows parallel runs`() {
        val first = running()
        val refused = call(RunAction.START, RunArguments(configuration = "Server"))
        assertEquals(ErrorCode.CONFLICT, refused.code())
        assertTrue(refused.message(), first.id.value in refused.message())
        assertTrue(execution.started.isEmpty())

        execution.configurations[0] = configuration("Server", parallel = true)
        assertTrue(call(RunAction.START, RunArguments(configuration = "Server")) is ToolResult.Success)
    }

    @Test
    fun `two concurrent starts of a configuration without parallel runs start it once`() = runBlocking {
        execution.beforeStart = { yield() }
        val calls = List(2) { async { control.execute(RunAction.START, RunArguments(configuration = "Server")) } }
        val results = calls.awaitAll()
        assertEquals(1, execution.started.size)
        assertEquals(1, results.count { it is ToolResult.Success })
        assertEquals(listOf(ErrorCode.CONFLICT), results.filterIsInstance<ToolResult.Failure>().map { it.error.code })
    }

    @Test
    fun `an ambiguous name asks for the type and the type disambiguates`() {
        execution.configurations += configuration("Server", typeId = "JarApplication", typeName = "JAR Application")
        val ambiguous = call(RunAction.START, RunArguments(configuration = "Server"))
        assertEquals(ErrorCode.AMBIGUOUS, ambiguous.code())
        assertTrue(
            ambiguous.message(),
            "JarApplication" in ambiguous.message() && "Application (" in ambiguous.message(),
        )
        val started = call(RunAction.START, RunArguments(configuration = "Server", type = "jar application"))
        assertEquals("JarApplication", (started.value() as RunOutcome.Process).process.configuration?.typeId)
        assertEquals(
            ErrorCode.NOT_FOUND,
            call(RunAction.START, RunArguments(configuration = "Server", type = "Gradle")).code(),
        )
        assertEquals(ErrorCode.NOT_FOUND, call(RunAction.START, RunArguments(configuration = "Nope")).code())
    }

    @Test
    fun `stop asks gracefully and reports the exit`() {
        val process = running()
        val stopped = call(RunAction.STOP, RunArguments(process = process.id.value)).value() as RunOutcome.Stopped
        assertEquals(listOf(process.id), execution.stopRequests)
        assertTrue(stopped.stop.terminated)
        assertFalse(stopped.stop.killed)
        assertEquals(ProcessState.Terminated(130), stopped.process.state)
    }

    @Test
    fun `stop waits for the timeout and kills only with force`() = runBlocking {
        execution.ignoresStop = true
        val process = running()
        val polite = async { control.execute(RunAction.STOP, RunArguments(process = "p1", timeoutSeconds = 5)) }
        settleUntil("the stop waits") { clock.sleeping == 1 }
        clock.advance(5.seconds)
        val result = polite.await().value() as RunOutcome.Stopped
        assertFalse(result.stop.terminated)
        assertEquals(5_000L, result.stop.waitedMillis)
        assertTrue(process.snapshot.state.isAlive)

        val forced = async { control.execute(RunAction.STOP, RunArguments(process = "p1", force = true)) }
        settleUntil("the stop waits") { clock.sleeping == 1 }
        clock.advance(10.seconds)
        val killed = forced.await().value() as RunOutcome.Stopped
        assertTrue(killed.stop.killed)
        assertTrue(killed.stop.terminated)
        assertEquals(ProcessState.Terminated(137), killed.process.state)
    }

    @Test
    fun `stop of an ended process changes nothing and a starting one cannot be stopped yet`() {
        val ended = running().apply { moveTo(ProcessState.Terminated(0)) }
        val result = call(RunAction.STOP, RunArguments(process = ended.id.value)).value() as RunOutcome.Stopped
        assertEquals(StopRequest.NOT_RUNNING, result.stop.request)
        assertTrue(execution.stopRequests.isEmpty())

        val starting = running(configuration("Other")).apply { moveTo(ProcessState.Starting) }
        assertEquals(ErrorCode.CONFLICT, call(RunAction.STOP, RunArguments(process = starting.id.value)).code())
    }

    @Test
    fun `stop by configuration needs exactly one alive process`() {
        execution.configurations[0] = configuration("Server", parallel = true)
        running()
        running()
        assertEquals(ErrorCode.AMBIGUOUS, call(RunAction.STOP, RunArguments(configuration = "Server")).code())
        catalog.all[0].moveTo(ProcessState.Terminated(0))
        assertTrue(call(RunAction.STOP, RunArguments(configuration = "Server")) is ToolResult.Success)
        assertEquals(listOf(ProcessId("p2")), execution.stopRequests)
    }

    @Test
    fun `restart stops the process and starts its configuration with the same executor`() {
        val old = running(executor = ExecutorKind.DEBUG)
        val restarted = call(RunAction.RESTART, RunArguments(process = old.id.value)).value() as RunOutcome.Restarted
        assertEquals(ProcessState.Terminated(130), restarted.previous?.state)
        assertEquals(listOf(server to ExecutorKind.DEBUG), execution.started)
        assertEquals("p2", restarted.process.id.value)
    }

    @Test
    fun `restart starts nothing when the process does not end in time`() = runBlocking {
        execution.ignoresStop = true
        running()
        val call = async { control.execute(RunAction.RESTART, RunArguments(process = "p1", timeoutSeconds = 1)) }
        settleUntil("the restart waits") { clock.sleeping == 1 }
        clock.advance(1.seconds)
        assertEquals(ErrorCode.CONFLICT, call.await().code())
        assertTrue(execution.started.isEmpty())
    }

    @Test
    fun `restart of an ended process just starts it again`() {
        running().moveTo(ProcessState.Terminated(1))
        val restarted = call(RunAction.RESTART, RunArguments(configuration = "Server")).value() as RunOutcome.Restarted
        assertNull(restarted.previous)
        assertEquals(1, execution.started.size)
    }

    @Test
    fun `restart needs a configuration that still exists`() {
        val tab = FakeProcess(ProcessId("p1"), configuration = null).also { catalog.all += it }
        assertEquals(ErrorCode.UNSUPPORTED, call(RunAction.RESTART, RunArguments(process = tab.id.value)).code())
        running()
        execution.configurations.clear()
        assertEquals(ErrorCode.NOT_FOUND, call(RunAction.RESTART, RunArguments(process = "p2")).code())
    }

    @Test
    fun `status and processes report the newest first`() {
        running().moveTo(ProcessState.Terminated(0))
        running()
        val all = (call(RunAction.PROCESSES, RunArguments()).value() as RunOutcome.Processes).processes
        assertEquals(listOf("p2", "p1"), all.map { it.id.value })
        val latest = call(RunAction.STATUS, RunArguments(configuration = "Server")).value() as RunOutcome.Process
        assertEquals("p2", latest.process.id.value)
    }

    @Test
    fun `handles are checked`() {
        assertEquals(ErrorCode.INVALID_ARGUMENT, call(RunAction.STATUS, RunArguments(process = "42")).code())
        assertEquals(ErrorCode.NOT_FOUND, call(RunAction.STATUS, RunArguments(process = "p9")).code())
        assertEquals(ErrorCode.INVALID_ARGUMENT, call(RunAction.STATUS, RunArguments()).code())
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            call(RunAction.STATUS, RunArguments(process = "p1", configuration = "Server")).code(),
        )
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            call(RunAction.STATUS, RunArguments(process = "p1", type = "x")).code(),
        )
    }

    @Test
    fun `parameters that do not apply to an action are rejected`() {
        val result = call(RunAction.START, RunArguments(configuration = "Server", lines = 5, force = true))
        assertEquals(ErrorCode.INVALID_ARGUMENT, result.code())
        assertTrue(result.message(), "lines, force" in result.message())
        assertTrue(execution.started.isEmpty())
        assertEquals(ErrorCode.INVALID_ARGUMENT, call(RunAction.PROCESSES, RunArguments(process = "p1")).code())
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            call(RunAction.STOP, RunArguments(process = "p1", timeoutSeconds = 0)).code(),
        )
    }
}
