package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** `wait_ready` strategies, driven by output and state events and a manual clock. */
class WaitReadyTest {

    private val process = FakeProcess(ProcessId("p1"), configuration("Server"))
    private val clock = ManualClock()
    private val portOpen = AtomicBoolean(false)
    private val probes = mutableListOf<Int>()
    private val control = RunControl.create(
        FakeCatalog(process),
        FakeExecution(FakeCatalog()),
        { error("no hot swap") },
        { port ->
            probes += port
            portOpen.get()
        },
        clock,
        Mutex(),
    )

    private fun waitReady(arguments: RunArguments) = runBlocking { control.execute(RunAction.WAIT_READY, arguments) }

    private fun readiness(result: ToolResult<RunOutcome>) = (result.value() as RunOutcome.Ready).readiness

    @Test
    fun `a ready line already captured ends the wait at once`() {
        process.print("booting\nREADY on 8080\n")
        val readiness = readiness(waitReady(RunArguments(process = "p1", pattern = "READY")))
        assertEquals(ReadinessOutcome.READY, readiness.outcome)
        assertEquals("READY on 8080", readiness.line?.text)
        assertEquals(2L, readiness.line?.seq)
        assertEquals("pattern READY", readiness.condition)
    }

    @Test
    fun `the wait ends when the ready line arrives`() = runBlocking {
        val wait = async { control.execute(RunAction.WAIT_READY, RunArguments(process = "p1", pattern = "READY")) }
        settleUntil("the wait has started") { clock.sleeping == 1 }
        process.print("tick 1\n")
        process.print("REA")
        settleUntil("not yet") { true }
        process.print("DY\n")
        val readiness = readiness(wait.await())
        assertEquals(ReadinessOutcome.READY, readiness.outcome)
        assertEquals(2L, readiness.line?.seq)
    }

    @Test
    fun `an unfinished line can make the process ready`() {
        process.print("Enter command> ")
        val readiness = readiness(waitReady(RunArguments(process = "p1", pattern = "command>")))
        assertEquals("Enter command> ", readiness.line?.text)
    }

    @Test
    fun `a failure pattern ends the wait early and wins over a ready pattern on the same line`() {
        process.print("ERROR but READY\n")
        val readiness = readiness(waitReady(RunArguments(process = "p1", pattern = "READY", failPattern = "ERROR")))
        assertEquals(ReadinessOutcome.FAILED, readiness.outcome)
        assertEquals("failPattern ERROR", readiness.condition)
    }

    @Test
    fun `the default failure pattern of Spring Boot applies when none is given`() {
        process.print("***************************\nAPPLICATION FAILED TO START\n")
        assertEquals(ReadinessOutcome.FAILED, readiness(waitReady(RunArguments(process = "p1"))).outcome)
    }

    @Test
    fun `default ready patterns recognise common server messages`() {
        process.print("Started DemoApplication in 2.345 seconds (process running for 2.9)\n")
        assertEquals(ReadinessOutcome.READY, readiness(waitReady(RunArguments(process = "p1"))).outcome)
    }

    @Test
    fun `the end of the process always ends the wait`() = runBlocking {
        val wait = async { control.execute(RunAction.WAIT_READY, RunArguments(process = "p1", pattern = "READY")) }
        settleUntil("the wait has started") { clock.sleeping == 1 }
        process.print("crash\n")
        process.moveTo(ProcessState.Terminated(3))
        val readiness = readiness(wait.await())
        assertEquals(ReadinessOutcome.ENDED, readiness.outcome)
        assertEquals("process ended with exit code 3", readiness.condition)
    }

    @Test
    fun `a ready line printed just before the end still counts`() {
        process.print("READY\n")
        process.moveTo(ProcessState.Terminated(0))
        assertEquals(
            ReadinessOutcome.READY,
            readiness(waitReady(RunArguments(process = "p1", pattern = "READY"))).outcome,
        )
    }

    @Test
    fun `a process that never started reports why`() {
        process.moveTo(ProcessState.NotStarted("Build failed"))
        val readiness = readiness(waitReady(RunArguments(process = "p1", pattern = "READY")))
        assertEquals(ReadinessOutcome.ENDED, readiness.outcome)
        assertEquals("process did not start: Build failed", readiness.condition)
    }

    @Test
    fun `the timeout ends the wait on the clock, not in real time`() = runBlocking {
        val wait = async {
            control.execute(RunAction.WAIT_READY, RunArguments(process = "p1", pattern = "NEVER", timeoutSeconds = 3))
        }
        settleUntil("the wait has started") { clock.sleeping == 1 }
        clock.advance(2.seconds)
        settleUntil("still waiting") { !wait.isCompleted }
        clock.advance(1.seconds)
        val readiness = readiness(wait.await())
        assertEquals(ReadinessOutcome.TIMEOUT, readiness.outcome)
        assertEquals(3_000L, readiness.waitedMillis)
        assertNull(readiness.line)
    }

    @Test
    fun `cancelling the call cancels the wait`() = runBlocking {
        val wait = async(start = CoroutineStart.UNDISPATCHED) {
            control.execute(RunAction.WAIT_READY, RunArguments(process = "p1", pattern = "NEVER"))
        }
        settleUntil("the wait has started") { clock.sleeping == 1 }
        wait.cancel()
        settleUntil("the clock is released") { clock.sleeping == 0 }
        assertTrue(wait.isCancelled)
    }

    @Test
    fun `a port strategy probes on the clock until the port accepts connections`() = runBlocking {
        val wait = async { control.execute(RunAction.WAIT_READY, RunArguments(process = "p1", port = 8080)) }
        settleUntil("probe and timeout sleep") { clock.sleeping == 2 }
        assertEquals(listOf(8080), probes)
        clock.advance(250.milliseconds)
        settleUntil("second probe") { probes.size == 2 }
        portOpen.set(true)
        clock.advance(250.milliseconds)
        val readiness = readiness(wait.await())
        assertEquals(ReadinessOutcome.READY, readiness.outcome)
        assertEquals("port 8080", readiness.condition)
    }

    @Test
    fun `since skips the lines a caller has already seen`() {
        process.print("READY old\nmore\n")
        process.print("READY new\n")
        val readiness = readiness(waitReady(RunArguments(process = "p1", pattern = "READY", since = 2)))
        assertEquals("READY new", readiness.line?.text)
    }

    @Test
    fun `invalid arguments are refused`() {
        assertEquals(ErrorCode.INVALID_ARGUMENT, waitReady(RunArguments(process = "p1", pattern = "(")).code())
        assertEquals(ErrorCode.INVALID_ARGUMENT, waitReady(RunArguments(process = "p1", port = 0)).code())
        assertEquals(ErrorCode.INVALID_ARGUMENT, waitReady(RunArguments(process = "p1", timeoutSeconds = 301)).code())
        assertEquals(ErrorCode.INVALID_ARGUMENT, waitReady(RunArguments(process = "p1", lines = 3)).code())
    }
}
