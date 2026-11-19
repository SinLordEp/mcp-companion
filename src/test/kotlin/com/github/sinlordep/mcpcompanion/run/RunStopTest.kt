package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.testing.ManualClock
import com.github.sinlordep.mcpcompanion.testing.settleUntil
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

/** stop over fake gateways: the graceful stop, the kill after the grace period, force; no IDE, no real time. */
class RunStopTest {

    private val server = configuration("Server")
    private val catalog = FakeCatalog()
    private val execution = FakeExecution(catalog, server)
    private val clock = ManualClock()
    private val control = RunControl.create(
        catalog,
        execution,
        { error("no hot swap") },
        FakeTestResults(),
        { false },
        FakeConsent(),
        FakeCodeRuns(),
        clock,
        Mutex(),
    )

    private fun call(action: RunAction, arguments: RunArguments) = runBlocking { control.execute(action, arguments) }

    private fun running(configuration: ConfigurationRef = server, executor: ExecutorKind = ExecutorKind.RUN) =
        FakeProcess(ProcessId.of(catalog.all.size + 1L), configuration, executor).also { catalog.all += it }

    @Test
    fun `a process that ignores the graceful stop is killed after the grace period`() = runBlocking {
        execution.ignoresStop = true
        val process = running()
        val call = async { control.execute(RunAction.STOP, RunArguments(process = "p1", timeoutSeconds = 5)) }
        settleUntil("the stop waits") { clock.sleeping == 1 }
        assertTrue("no kill during the grace period", execution.kills.isEmpty())
        clock.advance(5.seconds)
        val result = call.await().value() as RunOutcome.Stopped
        assertEquals(listOf(process.id), execution.stopRequests)
        assertEquals(listOf(process.id), execution.kills)
        assertEquals(StopRequest.STOPPING, result.stop.request)
        assertEquals(StopOutcome.KILLED, result.stop.outcome)
        assertTrue(result.stop.terminated)
        assertEquals(5_000L, result.stop.waitedMillis)
        assertEquals(ProcessState.Terminated(137), result.process.state)
    }

    @Test
    fun `a process that ignores the stop and cannot be killed is reported alive`() = runBlocking {
        execution.ignoresStop = true
        execution.killable = false
        val process = running()
        val call = async { control.execute(RunAction.STOP, RunArguments(process = "p1", timeoutSeconds = 2)) }
        settleUntil("the stop waits") { clock.sleeping == 1 }
        clock.advance(2.seconds)
        val result = call.await().value() as RunOutcome.Stopped
        assertEquals(StopOutcome.NOT_KILLABLE, result.stop.outcome)
        assertFalse(result.stop.terminated)
        assertFalse(result.stop.killed)
        assertTrue(process.snapshot.state.isAlive)
    }

    @Test
    fun `force kills at once without the graceful stop`() {
        val process = running()
        val result = call(RunAction.STOP, RunArguments(process = process.id.value, force = true)).value()
            as RunOutcome.Stopped
        assertTrue(execution.stopRequests.isEmpty())
        assertEquals(listOf(process.id), execution.kills)
        assertEquals(StopRequest.KILLING, result.stop.request)
        assertEquals(StopOutcome.KILLED, result.stop.outcome)
        assertEquals(0L, result.stop.waitedMillis)
    }

    @Test
    fun `force on a process that cannot be killed falls back to the graceful stop`() {
        execution.killable = false
        val process = running()
        val result = call(RunAction.STOP, RunArguments(process = process.id.value, force = true)).value()
            as RunOutcome.Stopped
        assertEquals(listOf(process.id), execution.stopRequests)
        assertEquals(StopOutcome.ENDED, result.stop.outcome)
        assertFalse(result.stop.killed)
    }

    @Test
    fun `stop asks gracefully and reports the exit`() {
        val process = running()
        val stopped = call(RunAction.STOP, RunArguments(process = process.id.value)).value() as RunOutcome.Stopped
        assertEquals(listOf(process.id), execution.stopRequests)
        assertEquals(StopOutcome.ENDED, stopped.stop.outcome)
        assertTrue(stopped.stop.terminated)
        assertFalse(stopped.stop.killed)
        assertTrue(execution.kills.isEmpty())
        assertEquals(ProcessState.Terminated(130), stopped.process.state)
    }
}
