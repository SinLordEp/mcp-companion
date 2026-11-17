package com.github.sinlordep.mcpcompanion.build

import com.github.sinlordep.mcpcompanion.common.build.BuildProblem
import com.github.sinlordep.mcpcompanion.common.build.BuildResult
import com.github.sinlordep.mcpcompanion.common.build.BuildRun
import com.github.sinlordep.mcpcompanion.common.build.MessageSource
import com.github.sinlordep.mcpcompanion.common.build.ProblemSeverity
import com.github.sinlordep.mcpcompanion.common.modulegraph.DependencyScope
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.testing.ManualClock
import com.github.sinlordep.mcpcompanion.testing.settleUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** `build` and `status` over a fake project model and builder; no IDE, no real time. */
class BuildControlTest {

    private val clock = ManualClock()

    /** app -> service -> core, tools -> core, app -> testkit (test), lonely. */
    private val environment = FakeBuildEnvironment()
        .modules("app", "core", "lonely", "service", "testkit", "tools")
        .dependsOn("app", "service")
        .dependsOn("service", "core")
        .dependsOn("tools", "core")
        .dependsOn("app", "testkit", DependencyScope.TEST)

    private fun test(block: suspend CoroutineScope.(BuildControl) -> Unit) = runBlocking {
        val builds = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            block(BuildControl.create(environment, BuildCoordinator(builds, clock), clock))
        } finally {
            builds.cancel()
        }
    }

    private suspend fun BuildControl.build(arguments: BuildArguments) = execute(BuildAction.BUILD, arguments)

    @Test
    fun `a module is built with what it depends on`() = test { control ->
        val outcome = control.build(BuildArguments(module = "service")).value() as BuildOutcome.Finished
        assertEquals(listOf(listOf("core", "service")), environment.builder.requests)
        assertEquals(BuildPlan("service", emptyList(), listOf("core", "service")), outcome.build.plan)
        assertEquals(BuildResult.SUCCEEDED, outcome.build.run.result)
    }

    @Test
    fun `includeDependents adds every module that depends on it, transitively, with their dependencies`() =
        test { control ->
            val outcome = control.build(BuildArguments(module = "core", includeDependents = true)).value()
                as BuildOutcome.Finished
            assertEquals(listOf("app", "service", "tools"), outcome.build.plan.dependents)
            assertEquals(listOf("app", "core", "service", "testkit", "tools"), outcome.build.plan.modules)
        }

    @Test
    fun `failures are refused before anything is built`() = test { control ->
        assertEquals(ErrorCode.INVALID_ARGUMENT, control.build(BuildArguments()).failure().code)
        assertEquals(ErrorCode.NOT_FOUND, control.build(BuildArguments(module = "Core")).failure().code)
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            control.build(BuildArguments(module = "core", timeoutSeconds = 0)).failure().code,
        )
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            control.execute(BuildAction.STATUS, BuildArguments(module = "core")).failure().code,
        )
        environment.trusted = false
        assertEquals(ErrorCode.CONFLICT, control.build(BuildArguments(module = "core")).failure().code)
        environment.trusted = true
        environment.canBuild = false
        assertEquals(ErrorCode.UNSUPPORTED, control.build(BuildArguments(module = "core")).failure().code)
        assertTrue(environment.builder.requests.isEmpty())
    }

    @Test
    fun `a timeout leaves the build running, status shows it and then its result`() = test { control ->
        environment.builder.immediate = null
        val call = async { control.build(BuildArguments(module = "core", timeoutSeconds = 10)) }
        settleUntil("the build waits") { clock.sleeping == 1 }
        clock.advance(10.seconds)
        val running = call.await().value() as BuildOutcome.StillRunning
        assertEquals("core", running.build.plan.module)
        assertEquals(10_000L, running.waitedMs)

        val during = control.execute(BuildAction.STATUS, BuildArguments()).value() as BuildOutcome.Status
        assertEquals("core", during.running?.plan?.module)
        assertNull(during.latest)

        val error = BuildProblem(ProblemSeverity.ERROR, "cannot find symbol", "core/A.java", 3, 9)
        environment.builder.finish(BuildRun(BuildResult.FAILED, listOf(error), MessageSource.BUILD_OUTPUT))
        repeat(SETTLE_STEPS) { if (control.statusNow().running != null) yield() }
        val after = control.statusNow()
        assertEquals(BuildResult.FAILED, after.latest?.run?.result)
        assertEquals(listOf(error), after.latest?.run?.problems)
        assertEquals(10_000L, after.latest?.durationMs)
    }

    @Test
    fun `a second build while one runs is refused and starts nothing`() = test { control ->
        environment.builder.immediate = null
        val first = async { control.build(BuildArguments(module = "core")) }
        settleUntil("the first build runs") { environment.builder.requests.size == 1 }
        val second = control.build(BuildArguments(module = "app"))
        assertEquals(ErrorCode.CONFLICT, second.failure().code)
        assertTrue(second.failure().message, "'core'" in second.failure().message)
        assertEquals(1, environment.builder.requests.size)

        environment.builder.finish(BuildRun(BuildResult.SUCCEEDED, emptyList(), MessageSource.COMPILER))
        assertTrue(first.await().value() is BuildOutcome.Finished)
        environment.builder.immediate = BuildRun(BuildResult.SUCCEEDED, emptyList(), MessageSource.COMPILER)
        assertTrue(control.build(BuildArguments(module = "app")).value() is BuildOutcome.Finished)
    }

    @Test
    fun `a build the IDE refuses ends as aborted with the reason and does not block the next one`() = test { control ->
        environment.builder.failure = IllegalStateException("no build runner")
        val outcome = control.build(BuildArguments(module = "core")).value() as BuildOutcome.Finished
        assertEquals(BuildResult.ABORTED, outcome.build.run.result)
        assertTrue(outcome.build.run.problems.single().message.contains("no build runner"))
        environment.builder.failure = null
        assertTrue(control.build(BuildArguments(module = "core")).value() is BuildOutcome.Finished)
    }

    private companion object {
        const val SETTLE_STEPS = 1_000
    }

    private suspend fun BuildControl.statusNow() =
        execute(BuildAction.STATUS, BuildArguments()).value() as BuildOutcome.Status
}
