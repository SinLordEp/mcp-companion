package com.github.sinlordep.mcpcompanion.run.ide

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.Executor
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.runners.ProgramRunner
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer

/**
 * Everything the IDE would ask the user about before a launch, decided by fixed rules instead:
 *
 * - an untrusted project (safe mode) never runs code;
 * - an invalid configuration (an error from `checkSettings`, where the IDE opens the configuration editor) is refused;
 *   warnings do not stop it, as in the IDE;
 * - settings with which the IDE would ask during the launch are refused ([LaunchQuestions]);
 * - a configuration that is not dumb-aware waits for indexing, as the IDE does.
 */
internal class LaunchChecks(private val project: Project, private val hotSwapSupports: () -> List<HotSwapSupport>) {

    suspend fun check(settings: RunnerAndConfigurationSettings, executor: Executor): ToolResult<ProgramRunner<*>> {
        if (!TrustedProjects.isProjectTrusted(project)) {
            return ToolResult.failure(
                ErrorCode.CONFLICT,
                "The project is not trusted (safe mode); the IDE does not run its code.",
                "Trust the project in the IDE first.",
            )
        }
        val check = { checkNow(settings, executor) }
        return if (DumbService.isDumbAware(settings.type)) readAction(check) else smartReadAction(project, check)
    }

    /**
     * Keeps builds that start [settings] from reloading classes into other debug sessions (where the IDE would ask)
     * until [process] has left the starting state.
     */
    fun guardBuilds(settings: RunnerAndConfigurationSettings, process: IdeTrackedProcess) {
        val supports = hotSwapSupports()
        if (supports.isEmpty()) return
        val lifetime = Disposer.newDisposable(process.lifetime, "mc_run start of ${settings.name}")
        supports.forEach { it.suppressBuildReload(project, settings.configuration, lifetime) }
        process.whenLaunched { Disposer.dispose(lifetime) }
    }

    private fun checkNow(settings: RunnerAndConfigurationSettings, executor: Executor): ToolResult<ProgramRunner<*>> {
        val configuration = settings.configuration
        val runner = ProgramRunner.getRunner(executor.id, configuration) ?: return ToolResult.failure(
            ErrorCode.UNSUPPORTED,
            "'${settings.name}' (${settings.type.displayName}) cannot be started with executor ${executor.id}.",
            "Use the other executor, or start it from the IDE.",
        )
        val invalid = invalidity(settings, executor)
        val question = LaunchQuestions.refusal(settings)
        return when {
            invalid != null -> ToolResult.failure(
                ErrorCode.CONFLICT,
                "'${settings.name}' is not valid: $invalid",
                "Fix the run configuration in the IDE (Run | Edit Configurations), then retry.",
            )

            !ExecutionTargetManager.canRun(configuration, ExecutionTargetManager.getActiveTarget(project)) ->
                ToolResult.failure(
                    ErrorCode.UNSUPPORTED,
                    "'${settings.name}' cannot run on the active execution target.",
                    "Select another run target in the IDE.",
                )

            question != null -> question

            else -> ToolResult.success(runner)
        }
    }

    private fun invalidity(settings: RunnerAndConfigurationSettings, executor: Executor): String? = try {
        settings.checkSettings(executor)
        null
    } catch (e: RuntimeConfigurationError) {
        e.localizedMessage ?: e.javaClass.simpleName
    } catch (_: RuntimeConfigurationException) {
        // A warning: the IDE runs the configuration anyway.
        null
    }
}
