package com.github.sinlordep.mcpcompanion.build

import com.github.sinlordep.mcpcompanion.common.build.BuildRun
import java.time.Instant

/**
 * What one build covers: [module] as requested, its [dependents] (when asked for), and [modules], every module the
 * build includes by the declared dependencies of the project model (the requested ones and all they depend on).
 */
data class BuildPlan(val module: String, val dependents: List<String>, val modules: List<String>)

/** A build that ended. [run] is the IDE's result with every problem (warnings included). */
data class FinishedBuild(val plan: BuildPlan, val startedAt: Instant, val durationMs: Long, val run: BuildRun)

/** A build that still runs. */
data class RunningBuild(val plan: BuildPlan, val startedAt: Instant)

/** What an `mc_build` action produced. */
sealed interface BuildOutcome {

    /** The build ended within the call. Problems are filtered by [includeWarnings]. */
    data class Finished(val build: FinishedBuild, val includeWarnings: Boolean) : BuildOutcome

    /** The timeout passed; the build goes on in the IDE and [BuildAction.STATUS] reports its end. */
    data class StillRunning(val build: RunningBuild, val waitedMs: Long) : BuildOutcome

    /** `status`: the build running now, if any, and the latest one that ended. */
    data class Status(val running: RunningBuild?, val latest: FinishedBuild?, val includeWarnings: Boolean) :
        BuildOutcome
}
