package com.github.sinlordep.mcpcompanion.run.ide

import com.github.sinlordep.mcpcompanion.run.ConfigurationRef
import com.github.sinlordep.mcpcompanion.run.ExecutorKind
import com.github.sinlordep.mcpcompanion.run.ProcessId
import com.github.sinlordep.mcpcompanion.run.ProcessSnapshot
import com.github.sinlordep.mcpcompanion.run.ProcessState
import com.intellij.execution.Executor
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import java.time.Instant

/** Translates the IDE's execution objects into the domain model of `mc_run`. */
internal object ProcessDescriptions {

    fun configurationOf(settings: RunnerAndConfigurationSettings): ConfigurationRef = ConfigurationRef(
        key = settings.uniqueID,
        name = settings.name,
        typeId = settings.type.id,
        typeName = settings.type.displayName,
        folder = settings.folderName,
        allowsParallelRuns = settings.configuration.isAllowRunningInParallel,
    )

    fun executorKind(executorId: String): ExecutorKind = when (executorId) {
        DefaultRunExecutor.EXECUTOR_ID -> ExecutorKind.RUN
        DefaultDebugExecutor.EXECUTOR_ID -> ExecutorKind.DEBUG
        else -> ExecutorKind.OTHER
    }

    fun executorOf(kind: ExecutorKind): Executor? = when (kind) {
        ExecutorKind.RUN -> DefaultRunExecutor.getRunExecutorInstance()
        ExecutorKind.DEBUG -> DefaultDebugExecutor.getDebugExecutorInstance()
        ExecutorKind.OTHER -> null
    }

    /**
     * A process of [environment] that is scheduled but has no OS process yet. [requested] is the configuration
     * `mc_run` was asked to start: a project that delegates running to Gradle runs it as a generated Gradle
     * configuration, but the process belongs to the one the client named (and `restart` starts that one again).
     */
    fun starting(
        environment: ExecutionEnvironment,
        now: Instant,
        requested: RunnerAndConfigurationSettings? = null,
    ): (ProcessId) -> ProcessSnapshot = { id ->
        val settings = requested ?: environment.runnerAndConfigurationSettings
        ProcessSnapshot(
            id = id,
            name = settings?.name ?: environment.runProfile.name,
            configuration = settings?.let(::configurationOf),
            executor = executorKind(environment.executor.id),
            executorId = environment.executor.id,
            state = ProcessState.Starting,
            startedAt = now,
        )
    }
}
