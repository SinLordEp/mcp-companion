package com.github.sinlordep.mcpcompanion.run.ide

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.run.ConfigurationRef
import com.github.sinlordep.mcpcompanion.run.ExecutionGateway
import com.github.sinlordep.mcpcompanion.run.ExecutorKind
import com.github.sinlordep.mcpcompanion.run.StopRequest
import com.github.sinlordep.mcpcompanion.run.TrackedProcess
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.KillableProcess
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Starts and stops run configurations without any of the IDE's questions. The IDE's own entry points
 * (`ExecutionManager.restartRunProfile`, `ProgramRunnerUtil.executeConfiguration`) ask whether to stop a running
 * instance and open the configuration editor for an invalid configuration; this gateway checks those cases itself
 * ([LaunchChecks]) and then hands the environment straight to its program runner, which is what those entry points do
 * once the user has answered. See `docs/notes-step3.md`.
 */
internal class IdeExecutionGateway(
    private val project: Project,
    private val tracker: ProcessTracker,
    private val checks: LaunchChecks,
) : ExecutionGateway {

    override suspend fun configurationsNamed(name: String): List<ConfigurationRef> =
        readAction { allSettings().filter { it.name == name }.map(ProcessDescriptions::configurationOf) }

    override suspend fun configuration(key: String): ConfigurationRef? =
        readAction { allSettings().find { it.uniqueID == key }?.let(ProcessDescriptions::configurationOf) }

    override suspend fun start(configuration: ConfigurationRef, executor: ExecutorKind): ToolResult<TrackedProcess> {
        val ideExecutor = ProcessDescriptions.executorOf(executor)
            ?: return ToolResult.failure(ErrorCode.INVALID_ARGUMENT, "executor must be run or debug.", null)
        val settings = readAction { allSettings().find { it.uniqueID == configuration.key } }
            ?: return ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "The run configuration '${configuration.name}' no longer exists.",
                "List the configurations with the official get_run_configurations tool.",
            )
        return checks.check(settings, ideExecutor).flatMap { launch(settings, ideExecutor) }
    }

    private suspend fun launch(
        settings: RunnerAndConfigurationSettings,
        executor: Executor,
    ): ToolResult<TrackedProcess> {
        lateinit var environment: ExecutionEnvironment
        // A runner may report a "started" run without a process; the execution manager then publishes nothing more.
        val withoutProcess = ProgramRunner.Callback { descriptor ->
            if (descriptor?.processHandler == null) tracker.notStarted(environment, NO_PROCESS)
        }
        // The builder picks the runner for the profile it builds: a project that delegates running to Gradle turns
        // the configuration into a Gradle run here, which needs the Gradle runner, not the one of the configuration.
        environment = ExecutionEnvironmentBuilder.create(executor, settings).activeTarget()
            .build(withoutProcess)
        // As the IDE's executeConfiguration(…, assignNewId = true): every start gets its own execution id.
        environment.assignNewExecutionId()
        val process = tracker.scheduled(environment, requested = settings)
        checks.guardBuilds(settings, process)
        return withContext(Dispatchers.EDT) {
            try {
                environment.runner.execute(environment)
                // Runners hand the start to the execution manager synchronously; one that did not (e.g. no run
                // profile state) will never report back.
                tracker.notStarted(environment, NOT_HANDED_OVER, unlessAnnounced = true)
                ToolResult.success(process)
            } catch (e: ExecutionException) {
                tracker.notStarted(environment, e)
                ToolResult.failure(
                    ErrorCode.CONFLICT,
                    "'${settings.name}' could not be started: ${e.message}",
                    "Fix the run configuration in the IDE, then retry.",
                )
            }
        }
    }

    override fun requestStop(process: TrackedProcess): StopRequest {
        val handler = tracker.handlerOf(process)
        return when {
            handler == null || handler.isProcessTerminated -> StopRequest.NOT_RUNNING
            handler.detachIsDefault() -> StopRequest.DETACHING.also { handler.detachProcess() }
            else -> StopRequest.STOPPING.also { handler.destroyProcess() }
        }
    }

    override fun kill(process: TrackedProcess): Boolean {
        val handler = tracker.handlerOf(process) as? KillableProcess ?: return false
        if (!handler.canKillProcess()) return false
        handler.killProcess()
        return true
    }

    private fun allSettings(): List<RunnerAndConfigurationSettings> = RunManager.getInstance(project).allSettings

    private companion object {
        val NO_PROCESS = ExecutionException("The runner finished the start without a process.")
        val NOT_HANDED_OVER =
            ExecutionException("The runner did not start anything (it gave the IDE no run state or process).")
    }
}
