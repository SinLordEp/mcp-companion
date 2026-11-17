package com.github.sinlordep.mcpcompanion.run.ide

import com.github.sinlordep.mcpcompanion.common.time.Clock
import com.github.sinlordep.mcpcompanion.run.ProcessId
import com.github.sinlordep.mcpcompanion.run.ProcessSnapshot
import com.github.sinlordep.mcpcompanion.run.ProcessState
import com.github.sinlordep.mcpcompanion.run.TrackedProcess
import com.github.sinlordep.mcpcompanion.run.output.ConsoleBuffer
import com.github.sinlordep.mcpcompanion.run.output.ConsoleStream
import com.intellij.execution.process.BaseProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One process the IDE runs, observed through its [ProcessHandler]: output goes into [console], state changes into
 * [snapshots], test events into [tests]. The listener lives as long as [lifetime], which the tracker disposes when it
 * releases the process.
 */
internal class IdeTrackedProcess(
    override val id: ProcessId,
    initial: ProcessSnapshot,
    val lifetime: Disposable,
    private val clock: Clock,
    private val onEnded: (IdeTrackedProcess) -> Unit,
) : TrackedProcess {

    private val state = MutableStateFlow(initial)
    override val snapshots: StateFlow<ProcessSnapshot> = state.asStateFlow()
    override val console: ConsoleBuffer = ConsoleBuffer()

    /** The IDE's test tree of this process, once a test event named it; released with this record. */
    val tests: ProcessTests = ProcessTests()

    @Volatile
    var handler: ProcessHandler? = null
        private set

    /** Whether the run tab of the process was closed or reused. */
    @Volatile
    var contentClosed: Boolean = false
        private set

    @Volatile
    var released: Boolean = false

    /** Whether the execution manager announced this start (only then does it report how the start ends). */
    @Volatile
    var announced: Boolean = false

    private val launchCallbacks = ArrayList<() -> Unit>()

    /** Runs [action] once the process has left the starting state (at once when it already has). */
    fun whenLaunched(action: () -> Unit) {
        val runNow = synchronized(launchCallbacks) {
            val starting = state.value.state == ProcessState.Starting
            if (starting) launchCallbacks += action
            !starting
        }
        if (runNow) action()
    }

    private fun launched() {
        val callbacks = synchronized(launchCallbacks) { launchCallbacks.toList().also { launchCallbacks.clear() } }
        callbacks.forEach { it() }
    }

    /**
     * Calls [release] when [content] (the run tab) is disposed after the process ended, or when the process ends
     * after its tab was closed. Detaches from [content] when this process is released first.
     */
    fun releaseWithContent(content: Disposable, release: () -> Unit) {
        val hook = Disposable {
            contentClosed = true
            if (snapshot.state.hasEnded) release()
        }
        if (Disposer.tryRegister(content, hook)) Disposer.register(lifetime) { Disposer.dispose(hook) }
    }

    /** Starts observing [processHandler]; later calls are ignored (the IDE reports a process more than once). */
    fun attach(processHandler: ProcessHandler) {
        synchronized(this) {
            if (handler != null) return
            handler = processHandler
        }
        val pid = pidOf(processHandler)
        state.update { if (it.state == ProcessState.Starting) it.copy(state = ProcessState.Running, pid = pid) else it }
        launched()
        processHandler.addProcessListener(Listener(), lifetime)
        if (processHandler.isProcessTerminated) ended(processHandler.exitCode ?: UNKNOWN_EXIT_CODE)
    }

    fun notStarted(reason: String?) {
        if (state.value.state == ProcessState.Starting) end(ProcessState.NotStarted(reason))
    }

    private fun ended(exitCode: Int) = end(ProcessState.Terminated(exitCode))

    /** Moves to [final] once, whichever thread reports the end first; the output is complete by then. */
    private fun end(final: ProcessState) {
        console.close()
        var changed = false
        state.update {
            changed = it.state.isAlive
            if (changed) it.copy(state = final, endedAt = clock.now()) else it
        }
        if (!changed) return
        launched()
        onEnded(this)
    }

    private inner class Listener : ProcessListener {
        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            console.append(event.text ?: return, streamOf(outputType))
        }

        override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
            state.update { if (it.state == ProcessState.Running) it.copy(state = ProcessState.Terminating) else it }
        }

        override fun processTerminated(event: ProcessEvent) = ended(event.exitCode)
    }

    private companion object {
        const val UNKNOWN_EXIT_CODE = -1

        fun streamOf(outputType: Key<*>): ConsoleStream = when {
            ProcessOutputType.isStderr(outputType) -> ConsoleStream.STDERR
            ProcessOutputType.isSystem(outputType) -> ConsoleStream.SYSTEM
            else -> ConsoleStream.STDOUT
        }

        fun pidOf(handler: ProcessHandler): Long? =
            (handler as? BaseProcessHandler<*>)?.let { runCatching { it.process.pid() }.getOrNull() }
    }
}
