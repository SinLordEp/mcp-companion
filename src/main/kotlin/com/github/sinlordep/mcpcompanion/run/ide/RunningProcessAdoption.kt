package com.github.sinlordep.mcpcompanion.run.ide

import com.github.sinlordep.mcpcompanion.run.Clock
import com.github.sinlordep.mcpcompanion.run.ConfigurationRef
import com.github.sinlordep.mcpcompanion.run.ProcessId
import com.github.sinlordep.mcpcompanion.run.ProcessSnapshot
import com.github.sinlordep.mcpcompanion.run.ProcessState
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Picks up processes that were already running when tracking began, i.e. started before this plugin was loaded:
 * their run tabs are listed by the run content manager. Their output from before is lost and the start time is the
 * adoption time; the configuration is known only when exactly one run configuration has the tab's name.
 */
internal class RunningProcessAdoption(private val project: Project, private val clock: Clock) {

    private val lock = Mutex()

    @Volatile
    private var done = false

    /** Adopts once; a call cancelled half-way leaves the work to the next call (adopting is idempotent per handler). */
    suspend fun adoptOnce(tracker: ProcessTracker) {
        if (done) return
        lock.withLock {
            if (done) return
            val running = withContext(Dispatchers.EDT) { runningTabs() }
            val now = clock.now()
            for (tab in running) {
                tracker.adopt(tab.handler, { id -> tab.snapshot(id, now) }, tab.descriptor)
            }
            done = true
        }
    }

    /** Runs on the EDT: the run content manager walks the tool windows. */
    private fun runningTabs(): List<RunningTab> {
        val contents = RunContentManager.getInstance(project)
        val settings = RunManager.getInstance(project).allSettings
        return contents.allDescriptors.mapNotNull { descriptor ->
            val handler = descriptor.processHandler?.takeUnless { it.isProcessTerminated } ?: return@mapNotNull null
            val executor = when (contents.getToolWindowByDescriptor(descriptor)?.id) {
                DefaultRunExecutor.getRunExecutorInstance().toolWindowId -> DefaultRunExecutor.EXECUTOR_ID
                DefaultDebugExecutor.getDebugExecutorInstance().toolWindowId -> DefaultDebugExecutor.EXECUTOR_ID
                else -> UNKNOWN_EXECUTOR
            }
            val configuration = settings.singleOrNull { it.name == descriptor.displayName }
            RunningTab(
                handler,
                descriptor,
                descriptor.displayName,
                configuration?.let(ProcessDescriptions::configurationOf),
                executor,
            )
        }
    }

    private class RunningTab(
        val handler: ProcessHandler,
        val descriptor: RunContentDescriptor,
        val name: String,
        val configuration: ConfigurationRef?,
        val executorId: String,
    ) {
        fun snapshot(id: ProcessId, now: Instant) = ProcessSnapshot(
            id = id,
            name = name,
            configuration = configuration,
            executor = ProcessDescriptions.executorKind(executorId),
            executorId = executorId,
            state = ProcessState.Starting,
            startedAt = now,
            outputComplete = false,
        )
    }

    private companion object {
        const val UNKNOWN_EXECUTOR = "unknown"
    }
}
