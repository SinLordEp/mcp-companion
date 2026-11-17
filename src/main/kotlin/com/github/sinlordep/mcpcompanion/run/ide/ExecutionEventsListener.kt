package com.github.sinlordep.mcpcompanion.run.ide

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project

/**
 * Feeds the project's execution events into [ProcessTracker]. Registered in `plugin.xml` as a project listener, so it
 * hears every start in the project, also those the user makes in the IDE, from the moment the plugin is loaded.
 *
 * `processStarting(…, handler)` comes before the IDE calls `startNotify()`, so attaching there captures the output
 * from the first byte (pinned by `ExecutionEventsContractTest`); `processStarted` is the fallback for runners that
 * notify earlier.
 */
internal class ExecutionEventsListener(private val project: Project) : ExecutionListener {

    private val tracker: ProcessTracker get() = ProcessTracker.getInstance(project)

    override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
        tracker.scheduled(env, byIde = true)
    }

    override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) =
        tracker.starting(env, handler)

    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) =
        tracker.starting(env, handler)

    override fun processNotStarted(executorId: String, env: ExecutionEnvironment, cause: Throwable?) =
        tracker.notStarted(env, cause)
}
