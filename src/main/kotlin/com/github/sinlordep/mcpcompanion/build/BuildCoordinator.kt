package com.github.sinlordep.mcpcompanion.build

import com.github.sinlordep.mcpcompanion.common.build.BuildProblem
import com.github.sinlordep.mcpcompanion.common.build.BuildResult
import com.github.sinlordep.mcpcompanion.common.build.BuildRun
import com.github.sinlordep.mcpcompanion.common.build.MessageSource
import com.github.sinlordep.mcpcompanion.common.build.ProblemSeverity
import com.github.sinlordep.mcpcompanion.common.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * The concurrency rule of `mc_build`, per project: one build at a time. A request while a build runs is refused, not
 * queued or joined: builds of different modules cannot share a result, and a queued call could spend its whole
 * timeout waiting. An incremental build right after the running one ends is cheap, so the caller retries.
 *
 * Builds run in [scope] (the project's), not in the caller's: a call whose timeout passed leaves its build running
 * and [latest] shows the result to later calls. One instance per project.
 */
class BuildCoordinator(private val scope: CoroutineScope, private val clock: Clock) {

    private val current = AtomicReference<Pending?>(null)

    @Volatile
    var latest: FinishedBuild? = null
        private set

    /** The build running now. */
    val running: RunningBuild? get() = current.get()?.build

    /** Either the new build ([Start.Started]) or, when one already runs, that one ([Start.Busy]). */
    sealed interface Start {
        data class Started(val build: RunningBuild, val result: Deferred<FinishedBuild>) : Start

        data class Busy(val running: RunningBuild) : Start
    }

    fun start(plan: BuildPlan, builder: ModuleBuilder): Start {
        val pending = Pending(RunningBuild(plan, clock.now()))
        while (true) {
            current.get()?.let { return Start.Busy(it.build) }
            if (current.compareAndSet(null, pending)) break
        }
        val result = scope.async {
            try {
                val run = runCatchingFailure { builder.build(plan.modules) }
                FinishedBuild(plan, pending.build.startedAt, elapsedSince(pending.build), run).also { latest = it }
            } finally {
                current.compareAndSet(pending, null)
            }
        }
        return Start.Started(pending.build, result)
    }

    private fun elapsedSince(build: RunningBuild): Long = Duration.between(build.startedAt, clock.now()).toMillis()

    /** A build that threw (the IDE refused it, or a gateway failed) ends as aborted with the reason. */
    @Suppress("TooGenericExceptionCaught") // Any failure of the IDE's build must end the run, never leave it running.
    private suspend fun runCatchingFailure(block: suspend () -> BuildRun): BuildRun = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        BuildRun(
            BuildResult.ABORTED,
            listOf(BuildProblem(ProblemSeverity.ERROR, "The IDE did not run the build: ${failure.message}")),
            MessageSource.NONE,
        )
    }

    private class Pending(val build: RunningBuild)
}
