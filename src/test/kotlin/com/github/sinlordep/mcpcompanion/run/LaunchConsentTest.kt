package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolError
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.testing.ManualClock
import com.github.sinlordep.mcpcompanion.testing.settleUntil
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The user's confirmation of launches: when it is asked, how often, in which order, and what a refusal does. */
class LaunchConsentTest {

    private val server = configuration("Server")
    private val catalog = FakeCatalog()
    private val execution = FakeExecution(catalog, server)
    private val consent = FakeConsent(required = true)
    private val clock = ManualClock()
    private var hotSwapReport: HotSwapReport = HotSwapReport.NotPossible("Server", "not a debug session")
    private val control = RunControl.create(
        catalog,
        execution,
        { ToolResult.success(hotSwapReport) },
        FakeTestResults(),
        { false },
        consent,
        clock,
        Mutex(),
    )

    private fun call(action: RunAction, arguments: RunArguments) = runBlocking { control.execute(action, arguments) }

    private fun start(configuration: String = "Server", executor: ExecutorKind? = null) =
        call(RunAction.START, RunArguments(configuration = configuration, executor = executor))

    private fun running(executor: ExecutorKind = ExecutorKind.RUN) =
        FakeProcess(ProcessId.of(catalog.all.size + 1L), server, executor).also { catalog.all += it }

    @Test
    fun `an allowed start asks once and names configuration, executor and purpose`() {
        val process = (start(executor = ExecutorKind.DEBUG).value() as RunOutcome.Process).process
        assertEquals(listOf(LaunchRequest(server, ExecutorKind.DEBUG, LaunchPurpose.START)), consent.questions)
        assertEquals(ProcessState.Starting, process.state)
        assertEquals(listOf(server to ExecutorKind.DEBUG), execution.started)
    }

    @Test
    fun `a denied start fails with DENIED, starts nothing and leaves no process`() {
        consent.answer = ConsentAnswer.Denied
        val denied = start()
        assertEquals(ErrorCode.DENIED, denied.code())
        assertTrue(denied.message(), "denied" in denied.message() && "Nothing was started" in denied.message())
        assertTrue((denied as ToolResult.Failure).error.nextStep!!.startsWith("Do not retry"))
        assertTrue(execution.started.isEmpty())
        assertTrue((call(RunAction.PROCESSES, RunArguments()).value() as RunOutcome.Processes).processes.isEmpty())
    }

    @Test
    fun `without confirmation nothing is asked`() {
        consent.required = false
        assertTrue(start() is ToolResult.Success)
        assertTrue(consent.questions.isEmpty())
    }

    @Test
    fun `launch without confirmation takes effect with Allow only`() {
        consent.answer = ConsentAnswer.Denied
        start()
        assertTrue("a refusal never turns confirmation off", consent.isRequired())

        consent.answer = ConsentAnswer.Allowed(stopAsking = true)
        assertTrue(start() is ToolResult.Success)
        assertFalse(consent.isRequired())
        catalog.all.single().moveTo(ProcessState.Terminated(0))
        assertTrue(start() is ToolResult.Success)
        assertEquals("the second start was not asked", 2, consent.questions.size)
    }

    @Test
    fun `nobody to ask is UNSUPPORTED and starts nothing`() {
        consent.answer = ConsentAnswer.Unavailable("headless")
        val result = start()
        assertEquals(ErrorCode.UNSUPPORTED, result.code())
        assertTrue(result.message(), "headless" in result.message())
        assertTrue(execution.started.isEmpty())
    }

    @Test
    fun `restart asks once, before it stops anything`() {
        val old = running(ExecutorKind.DEBUG)
        consent.answer = ConsentAnswer.Denied
        assertEquals(ErrorCode.DENIED, call(RunAction.RESTART, RunArguments(process = "p1")).code())
        assertTrue(execution.stopRequests.isEmpty())
        assertEquals(ProcessState.Running, old.snapshot.state)

        consent.answer = ConsentAnswer.Allowed(false)
        val restarted = call(RunAction.RESTART, RunArguments(process = "p1")).value() as RunOutcome.Restarted
        assertEquals("p2", restarted.process.id.value)
        val asked = LaunchRequest(server, ExecutorKind.DEBUG, LaunchPurpose.RESTART, stopsRunning = true)
        assertEquals(listOf(asked, asked), consent.questions)
    }

    @Test
    fun `restart of an ended process asks once without stopping`() {
        running().moveTo(ProcessState.Terminated(0))
        assertTrue(call(RunAction.RESTART, RunArguments(process = "p1")) is ToolResult.Success)
        assertEquals(listOf(LaunchRequest(server, ExecutorKind.RUN, LaunchPurpose.RESTART)), consent.questions)
    }

    @Test
    fun `hotswap asks only when it restarts, once`() {
        hotSwapReport = HotSwapReport.Reloaded("Server", listOf("a.B"), emptyList())
        running(ExecutorKind.DEBUG)
        assertTrue(call(RunAction.HOTSWAP, RunArguments(process = "p1", restartOnFailure = true)) is ToolResult.Success)
        assertTrue("a reload without restart asks nothing", consent.questions.isEmpty())

        hotSwapReport = HotSwapReport.NotPossible("Server", "method added")
        consent.answer = ConsentAnswer.Denied
        val denied = call(RunAction.HOTSWAP, RunArguments(process = "p1", restartOnFailure = true))
        assertEquals(ErrorCode.DENIED, denied.code())
        assertTrue(denied.message(), denied.message().startsWith("Nothing was reloaded: method added"))
        assertTrue(denied.message(), "denied the request to restart 'Server'" in denied.message())
        assertTrue(execution.stopRequests.isEmpty())

        consent.answer = ConsentAnswer.Allowed(false)
        val swapped = call(RunAction.HOTSWAP, RunArguments(process = "p1", restartOnFailure = true))
            .value() as RunOutcome.HotSwapped
        assertEquals("p2", swapped.restarted?.id?.value)
        assertEquals(
            List(2) { LaunchRequest(server, ExecutorKind.DEBUG, LaunchPurpose.HOTSWAP_RESTART, stopsRunning = true) },
            consent.questions,
        )
    }

    @Test
    fun `refusals that need no user come before the question`() {
        assertEquals(ErrorCode.NOT_FOUND, start("Nope").code())
        execution.launchRefusal = ToolResult.Failure(
            ToolError(ErrorCode.CONFLICT, "'Server' is not valid"),
        )
        assertEquals(ErrorCode.CONFLICT, start().code())
        assertEquals(ErrorCode.CONFLICT, call(RunAction.RESTART, RunArguments(process = running().id.value)).code())
        execution.launchRefusal = null
        assertEquals("already running", ErrorCode.CONFLICT, start().code())
        catalog.all.single().moveTo(ProcessState.Starting)
        assertEquals("still starting", ErrorCode.CONFLICT, call(RunAction.RESTART, RunArguments(process = "p1")).code())
        assertTrue(consent.questions.isEmpty())
        assertTrue(execution.started.isEmpty())
    }

    @Test
    fun `cancelling the call withdraws the question and starts nothing`() = runBlocking {
        consent.answers = Channel()
        val call = async { control.execute(RunAction.START, RunArguments(configuration = "Server")) }
        settleUntil("the question is asked") { consent.questions.size == 1 }
        call.cancel()
        settleUntil("the question is withdrawn") { consent.withdrawn.size == 1 }
        assertTrue(execution.started.isEmpty())

        consent.answers = null
        assertTrue("the start lock was released", start() is ToolResult.Success)
    }

    @Test
    fun `an unanswered question times out as DENIED`() = runBlocking {
        consent.answers = Channel()
        val call = async { control.execute(RunAction.START, RunArguments(configuration = "Server")) }
        settleUntil("the question waits") { clock.sleeping == 1 }
        clock.advance(LaunchApproval.DEFAULT_TIMEOUT)
        val result = call.await()
        assertEquals(ErrorCode.DENIED, result.code())
        assertTrue(result.message(), "did not answer" in result.message())
        assertEquals(1, consent.withdrawn.size)
        assertTrue(execution.started.isEmpty())
    }

    @Test
    fun `concurrent starts are asked one after the other and start once`() = runBlocking {
        val answers = Channel<ConsentAnswer>()
        consent.answers = answers
        val first = async { control.execute(RunAction.START, RunArguments(configuration = "Server")) }
        val second = async { control.execute(RunAction.START, RunArguments(configuration = "Server")) }
        settleUntil("the first question") { consent.questions.size == 1 }
        // While the question is open, read-only actions answer; the second start waits behind the first.
        val listed = control.execute(RunAction.PROCESSES, RunArguments()).value() as RunOutcome.Processes
        assertTrue(listed.processes.isEmpty())
        assertEquals(1, consent.questions.size)

        answers.send(ConsentAnswer.Allowed(false))
        assertTrue(first.await() is ToolResult.Success)
        assertEquals("the second sees the first process, without a question", ErrorCode.CONFLICT, second.await().code())
        assertEquals(1, consent.questions.size)
        assertEquals(1, execution.started.size)
    }

    @Test
    fun `concurrent starts of a parallel configuration are both asked, in turn`() = runBlocking {
        execution.configurations[0] = configuration("Server", parallel = true)
        val answers = Channel<ConsentAnswer>()
        consent.answers = answers
        val first = async { control.execute(RunAction.START, RunArguments(configuration = "Server")) }
        val second = async { control.execute(RunAction.START, RunArguments(configuration = "Server")) }
        settleUntil("the first question") { consent.questions.size == 1 }
        answers.send(ConsentAnswer.Denied)
        assertEquals(ErrorCode.DENIED, first.await().code())
        settleUntil("the second question") { consent.questions.size == 2 }
        answers.send(ConsentAnswer.Allowed(false))
        assertTrue(second.await() is ToolResult.Success)
        assertEquals(1, execution.started.size)
    }
}
