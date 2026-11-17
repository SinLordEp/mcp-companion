package com.github.sinlordep.mcpcompanion.toolsets.build

import com.github.sinlordep.mcpcompanion.build.BuildOutcome
import com.github.sinlordep.mcpcompanion.build.BuildPlan
import com.github.sinlordep.mcpcompanion.build.FinishedBuild
import com.github.sinlordep.mcpcompanion.build.RunningBuild
import com.github.sinlordep.mcpcompanion.common.build.BuildProblem
import com.github.sinlordep.mcpcompanion.common.build.BuildResult
import com.github.sinlordep.mcpcompanion.common.build.BuildRun
import com.github.sinlordep.mcpcompanion.common.build.MessageSource
import com.github.sinlordep.mcpcompanion.common.build.ProblemSeverity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The mapping of `mc_build` outcomes to MCP output: states, counts, the warning filter, notes. */
class BuildOutputTest {

    private val started = Instant.parse("2026-11-17T10:00:00Z")
    private val plan = BuildPlan("app.main", listOf("tools.main"), listOf("app.main", "lib.main", "tools.main"))
    private val error = BuildProblem(ProblemSeverity.ERROR, "cannot find symbol", "app/src/A.java", 5, 19)
    private val warning = BuildProblem(ProblemSeverity.WARNING, "deprecated", "lib/src/L.java", 7, 24)

    private fun finished(run: BuildRun, includeWarnings: Boolean = false) =
        BuildOutput.from(BuildOutcome.Finished(FinishedBuild(plan, started, 1234, run), includeWarnings))

    @Test
    fun `a failed build lists errors with positions, counts warnings, and lists them only on request`() {
        val run = BuildRun(BuildResult.FAILED, listOf(error, warning), MessageSource.BUILD_OUTPUT, omittedProblems = 3)
        val output = finished(run)
        assertEquals("finished", output.state)
        val build = checkNotNull(output.build)
        assertEquals("failed", build.outcome)
        assertEquals("build_output", build.messages)
        assertEquals(1, build.errors)
        assertEquals(1, build.warnings)
        assertEquals(listOf(BuildProblemOutput("error", "cannot find symbol", "app/src/A.java", 5, 19)), build.problems)
        assertEquals(3, build.omittedProblems)
        assertEquals(listOf("app.main", "lib.main", "tools.main"), build.modules)
        assertEquals("2026-11-17T10:00:00Z", build.startedAt)
        assertNull(build.note)
        assertEquals(2, checkNotNull(finished(run, includeWarnings = true).build).problems.size)
    }

    @Test
    fun `a failure without a readable error says where the messages are`() {
        val none = checkNotNull(finished(BuildRun(BuildResult.FAILED, emptyList(), MessageSource.NONE)).build)
        assertTrue(none.note!!.contains("build_project"))
        val output = checkNotNull(finished(BuildRun(BuildResult.FAILED, emptyList(), MessageSource.BUILD_OUTPUT)).build)
        assertTrue(output.note!!.contains("known format"))
        assertNull(checkNotNull(finished(BuildRun(BuildResult.SUCCEEDED, emptyList(), MessageSource.NONE)).build).note)
        val aborted = checkNotNull(finished(BuildRun(BuildResult.ABORTED, emptyList(), MessageSource.NONE)).build)
        assertTrue(aborted.note!!.contains("aborted"))
    }

    @Test
    fun `running and status states serialise`() {
        val running = BuildOutput.from(BuildOutcome.StillRunning(RunningBuild(plan, started), 300_000))
        assertEquals("running", running.state)
        assertEquals(300_000L, running.running?.waitedMs)
        val idle = BuildOutput.from(BuildOutcome.Status(null, null, includeWarnings = false))
        assertEquals("idle", idle.state)
        val json = Json.encodeToString(BuildOutput.serializer(), running)
        assertTrue(json, "\"module\":\"app.main\"" in json && "\"nextStep\"" in json)
    }
}
