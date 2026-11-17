package com.github.sinlordep.mcpcompanion.toolsets.build

import com.github.sinlordep.mcpcompanion.build.BuildOutcome
import com.github.sinlordep.mcpcompanion.build.FinishedBuild
import com.github.sinlordep.mcpcompanion.build.RunningBuild
import com.github.sinlordep.mcpcompanion.common.build.BuildProblem
import com.github.sinlordep.mcpcompanion.common.build.BuildResult
import com.github.sinlordep.mcpcompanion.common.build.MessageSource
import com.github.sinlordep.mcpcompanion.common.build.ProblemSeverity
import kotlinx.serialization.Serializable

/**
 * MCP output model of `mc_build`. state: finished (see build), running (the timeout passed; see running) or, for
 * status, idle. status also shows the latest build that ended.
 */
@Serializable
data class BuildOutput(
    val state: String,
    val build: BuildResultOutput? = null,
    val running: RunningBuildOutput? = null,
) {
    companion object {
        fun from(outcome: BuildOutcome): BuildOutput = when (outcome) {
            is BuildOutcome.Finished ->
                BuildOutput("finished", build = BuildResultOutput.from(outcome.build, outcome.includeWarnings))

            is BuildOutcome.StillRunning -> BuildOutput(
                "running",
                running = RunningBuildOutput.from(outcome.build, outcome.waitedMs),
            )

            is BuildOutcome.Status -> BuildOutput(
                state = if (outcome.running != null) "running" else "idle",
                build = outcome.latest?.let { BuildResultOutput.from(it, outcome.includeWarnings) },
                running = outcome.running?.let { RunningBuildOutput.from(it, null) },
            )
        }
    }
}

/** A build that still runs; waitedMs: how long this call waited. */
@Serializable
data class RunningBuildOutput(
    val module: String,
    val dependents: List<String> = emptyList(),
    val startedAt: String,
    val waitedMs: Long? = null,
    val nextStep: String,
) {
    companion object {
        fun from(build: RunningBuild, waitedMs: Long?) = RunningBuildOutput(
            module = build.plan.module,
            dependents = build.plan.dependents,
            startedAt = build.startedAt.toString(),
            waitedMs = waitedMs,
            nextStep = "The build goes on in the IDE; call action=status for its result.",
        )
    }
}

/**
 * One build that ended. outcome: succeeded, failed or aborted. modules: every module the build included (the module,
 * its dependents when asked, and all modules they depend on). messages: compiler, build_output or none (see the tool
 * description). errors and warnings count all problems; problems lists errors, and warnings with includeWarnings.
 */
@Serializable
data class BuildResultOutput(
    val outcome: String,
    val module: String,
    val dependents: List<String> = emptyList(),
    val modules: List<String>,
    val startedAt: String,
    val durationMs: Long,
    val messages: String,
    val errors: Int,
    val warnings: Int,
    val problems: List<BuildProblemOutput> = emptyList(),
    val omittedProblems: Int? = null,
    val note: String? = null,
) {
    companion object {
        fun from(build: FinishedBuild, includeWarnings: Boolean): BuildResultOutput {
            val run = build.run
            val shown = run.problems.filter { includeWarnings || it.severity == ProblemSeverity.ERROR }
            val errors = run.problems.count { it.severity == ProblemSeverity.ERROR }
            return BuildResultOutput(
                outcome = run.result.name.lowercase(),
                module = build.plan.module,
                dependents = build.plan.dependents,
                modules = build.plan.modules,
                startedAt = build.startedAt.toString(),
                durationMs = build.durationMs,
                messages = run.source.name.lowercase(),
                errors = errors,
                warnings = run.problems.size - errors,
                problems = shown.map(BuildProblemOutput::from),
                omittedProblems = run.omittedProblems.takeIf { it > 0 },
                note = noteFor(run.result, run.source, errors),
            )
        }

        private fun noteFor(result: BuildResult, source: MessageSource, errors: Int): String? = when {
            result == BuildResult.ABORTED && errors == 0 ->
                "The IDE reported the build as aborted (cancelled, or the " +
                    "build tool stopped before it compiled) without a message this tool can read; the official " +
                    "build_project tool lists the Build tool window's messages."

            result != BuildResult.FAILED || errors > 0 -> null

            source == MessageSource.NONE ->
                "The build failed without a message this tool can read; the official " +
                    "build_project tool lists the messages of the Build tool window."

            else ->
                "The build failed, but its output had no compiler error in a known format (e.g. a build " +
                    "script or dependency problem); the official build_project tool lists the Build tool window's messages."
        }
    }
}

/** severity: error or warning; path as read_file takes it; line and column 1-based, absent when not given. */
@Serializable
data class BuildProblemOutput(
    val severity: String,
    val message: String,
    val path: String? = null,
    val line: Int? = null,
    val column: Int? = null,
) {
    companion object {
        fun from(problem: BuildProblem) = BuildProblemOutput(
            problem.severity.name.lowercase(),
            problem.message,
            problem.path,
            problem.line,
            problem.column,
        )
    }
}
