package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.time.Clock
import com.github.sinlordep.mcpcompanion.run.output.ConsoleBuffer
import com.github.sinlordep.mcpcompanion.run.output.ConsoleStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.yield
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.toJavaDuration

fun configuration(
    name: String,
    typeId: String = "Application",
    typeName: String = "Application",
    parallel: Boolean = false,
) = ConfigurationRef("$typeId.$name", name, typeId, typeName, folder = null, allowsParallelRuns = parallel)

/** A process under the test's control. */
class FakeProcess(
    override val id: ProcessId,
    configuration: ConfigurationRef?,
    executor: ExecutorKind = ExecutorKind.RUN,
    state: ProcessState = ProcessState.Running,
) : TrackedProcess {
    private val flow = MutableStateFlow(
        ProcessSnapshot(
            id = id,
            name = configuration?.name ?: "tab",
            configuration = configuration,
            executor = executor,
            executorId = executor.name.lowercase(),
            state = state,
            startedAt = Instant.EPOCH,
        ),
    )
    override val snapshots: StateFlow<ProcessSnapshot> = flow
    override val console = ConsoleBuffer()

    fun print(text: String, stream: ConsoleStream = ConsoleStream.STDOUT) = console.append(text, stream)

    /** As if tracking began after the process had started. */
    fun adopted() = flow.update { it.copy(outputComplete = false) }

    fun moveTo(next: ProcessState) {
        if (next.hasEnded) console.close()
        flow.update { it.copy(state = next) }
    }
}

class FakeCatalog(vararg initial: FakeProcess) : ProcessCatalog {
    val all: MutableList<FakeProcess> = initial.toMutableList()

    override suspend fun processes(): List<TrackedProcess> = all.reversed()

    override suspend fun find(id: ProcessId): TrackedProcess? = all.find { it.id == id }
}

/**
 * Configurations and starts in memory. A stop ends the process with [stopExitCode] unless [ignoresStop]; a kill ends
 * it with 137 unless [killable] is false.
 */
class FakeExecution(private val catalog: FakeCatalog, vararg configurations: ConfigurationRef) : ExecutionGateway {
    val configurations: MutableList<ConfigurationRef> = configurations.toMutableList()
    val started = mutableListOf<Pair<ConfigurationRef, ExecutorKind>>()
    val stopRequests = mutableListOf<ProcessId>()
    val kills = mutableListOf<ProcessId>()
    var ignoresStop = false
    var killable = true
    var stopExitCode = 130
    var startFailure: ToolResult.Failure? = null

    /** Runs inside [start] before the process exists, e.g. to let another coroutine interleave. */
    var beforeStart: suspend () -> Unit = {}

    override suspend fun configurationsNamed(name: String) = configurations.filter { it.name == name }

    override suspend fun configuration(key: String) = configurations.find { it.key == key }

    override suspend fun start(configuration: ConfigurationRef, executor: ExecutorKind): ToolResult<TrackedProcess> {
        startFailure?.let { return it }
        beforeStart()
        started += configuration to executor
        val process = FakeProcess(ProcessId.of(catalog.all.size + 1L), configuration, executor, ProcessState.Starting)
        catalog.all += process
        return ToolResult.success(process)
    }

    override fun requestStop(process: TrackedProcess): StopRequest {
        stopRequests += process.id
        if (!ignoresStop) (process as FakeProcess).moveTo(ProcessState.Terminated(stopExitCode))
        return StopRequest.STOPPING
    }

    override fun kill(process: TrackedProcess): Boolean {
        if (!killable) return false
        kills += process.id
        (process as FakeProcess).moveTo(ProcessState.Terminated(137))
        return true
    }
}

fun <T> ToolResult<T>.value(): T =
    (this as? ToolResult.Success)?.value ?: throw AssertionError("expected success: $this")

fun ToolResult<*>.code(): ErrorCode =
    (this as? ToolResult.Failure)?.error?.code ?: throw AssertionError("expected failure: $this")

fun ToolResult<*>.message(): String = (this as ToolResult.Failure).error.message
