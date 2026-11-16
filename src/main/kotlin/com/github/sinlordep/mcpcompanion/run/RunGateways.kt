package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.run.output.ConsoleBuffer
import kotlinx.coroutines.flow.StateFlow

/** A process the IDE runs or ran, with its live state and captured output. */
interface TrackedProcess {
    val id: ProcessId

    /** The current snapshot; emits on every state change. */
    val snapshots: StateFlow<ProcessSnapshot>

    val console: ConsoleBuffer

    val snapshot: ProcessSnapshot get() = snapshots.value
}

/** Gateway boundary: the processes of one project, including those the user started in the IDE. */
interface ProcessCatalog {
    /** Every retained process, most recently started first. */
    suspend fun processes(): List<TrackedProcess>

    suspend fun find(id: ProcessId): TrackedProcess?
}

/** Gateway boundary: run configurations and the IDE's execution machinery. Never opens UI that waits for the user. */
interface ExecutionGateway {
    /** The run configurations named [name] (several when types share the name). */
    suspend fun configurationsNamed(name: String): List<ConfigurationRef>

    /** The run configuration with the IDE's unique [key], if it still exists. */
    suspend fun configuration(key: String): ConfigurationRef?

    /**
     * Starts [configuration] with [executor] and returns at once with the tracked process (state [ProcessState.Starting]
     * until a process exists). Fails when the configuration is invalid or cannot run with that executor.
     */
    suspend fun start(configuration: ConfigurationRef, executor: ExecutorKind): ToolResult<TrackedProcess>

    /** What the IDE's Stop does: ask [process] to end (or detach, for attached processes). */
    fun requestStop(process: TrackedProcess): StopRequest

    /** Kills [process] at once; false when it cannot be killed. */
    fun kill(process: TrackedProcess): Boolean
}

/** How a stop request was carried out. */
enum class StopRequest { STOPPING, DETACHING, NOT_RUNNING }

/** Gateway boundary: reloading changed code into a running debug session (JVM HotSwap). */
fun interface HotSwapGateway {
    suspend fun hotSwap(process: TrackedProcess): ToolResult<HotSwapReport>
}

/** Gateway boundary: whether something accepts TCP connections on a local port. */
fun interface PortProbe {
    suspend fun isOpen(port: Int): Boolean
}
