package com.github.sinlordep.mcpcompanion.build

import com.github.sinlordep.mcpcompanion.common.modulegraph.DependencyGraph
import com.github.sinlordep.mcpcompanion.common.modulegraph.GraphDirection
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.time.Clock
import com.github.sinlordep.mcpcompanion.common.time.timeoutOf
import com.github.sinlordep.mcpcompanion.common.time.withTimeout
import java.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * `build`: builds one module with the modules it depends on, and with `includeDependents` also every module that
 * depends on it (transitively) with theirs, through the IDE's build path. Waits up to the timeout; the build goes on
 * after it (see [BuildCoordinator]). Refused in an untrusted project, where the IDE would not run build scripts.
 */
class BuildModule(
    private val environment: BuildEnvironment,
    private val coordinator: BuildCoordinator,
    private val clock: Clock,
) : BuildActionHandler {

    override val action = BuildAction.BUILD
    override val parameters = setOf(
        BuildParameter.MODULE,
        BuildParameter.INCLUDE_DEPENDENTS,
        BuildParameter.INCLUDE_WARNINGS,
        BuildParameter.TIMEOUT,
    )

    override suspend fun handle(request: BuildArguments): ToolResult<BuildOutcome> {
        val module = request.module?.takeIf { it.isNotBlank() } ?: return missingModule()
        return timeoutOf(request.timeoutSeconds, DEFAULT_TIMEOUT, MAX_TIMEOUT).flatMap { timeout ->
            val builder = environment.builder()
            when {
                !environment.isTrusted() -> untrusted()

                builder == null -> unsupported()

                else -> plan(module, request.includeDependents == true).flatMap { plan ->
                    when (val start = coordinator.start(plan, builder)) {
                        is BuildCoordinator.Start.Busy -> busy(start.running)

                        is BuildCoordinator.Start.Started -> {
                            val finished = clock.withTimeout(timeout) { start.result.await() }
                            ToolResult.success(
                                finished?.let { BuildOutcome.Finished(it, request.includeWarnings == true) }
                                    ?: BuildOutcome.StillRunning(start.build, waitedMs(start.build)),
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun plan(module: String, includeDependents: Boolean): ToolResult<BuildPlan> {
        val graph = DependencyGraph(environment.moduleGraph())
        if (module !in graph) {
            return ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "No module named '$module'.",
                "Use an exact module name from mc_modules list (Gradle: per source set, e.g. project.lib.main).",
            )
        }
        val dependents = if (includeDependents) {
            (graph.walk(module, GraphDirection.DEPENDENTS, Int.MAX_VALUE).reached - module).sorted()
        } else {
            emptyList()
        }
        val modules = (listOf(module) + dependents)
            .flatMap { graph.walk(it, GraphDirection.DEPENDENCIES, Int.MAX_VALUE).reached }
            .distinct()
            .sorted()
        return ToolResult.success(BuildPlan(module, dependents, modules))
    }

    private fun waitedMs(build: RunningBuild): Long = Duration.between(build.startedAt, clock.now()).toMillis()

    private fun missingModule(): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.INVALID_ARGUMENT,
        "action=build needs module.",
        "Pass an exact module name from mc_modules list.",
    )

    private fun untrusted(): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.CONFLICT,
        "The project is not trusted (safe mode); the IDE does not build it or run its build scripts.",
        "Trust the project in the IDE first.",
    )

    private fun unsupported(): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.UNSUPPORTED,
        "This IDE cannot build modules (the Java plugin is not available).",
    )

    private fun busy(running: RunningBuild): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.CONFLICT,
        "A build of '${running.plan.module}' started by mc_build runs since ${running.startedAt}; one build at a " +
            "time. Nothing was started.",
        "Call action=status until it ended, then retry (an incremental build after it is quick).",
    )

    private companion object {
        val DEFAULT_TIMEOUT = 5.minutes
        val MAX_TIMEOUT = 30.minutes
    }
}
