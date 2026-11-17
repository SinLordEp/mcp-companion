package com.github.sinlordep.mcpcompanion.common.build

import org.junit.Assert.assertEquals
import org.junit.Test

/** How the IDE's flags and the messages read make a build's result. */
class BuildResultTest {

    private val error = BuildProblem(ProblemSeverity.ERROR, "cannot find symbol", "A.java", 1, 2)
    private val warning = BuildProblem(ProblemSeverity.WARNING, "deprecated", "A.java", 3, 4)

    @Test
    fun `errors reported by the IDE fail the build`() {
        assertEquals(BuildResult.FAILED, BuildResult.of(aborted = false, hasErrors = true, problems = emptyList()))
    }

    @Test
    fun `an aborted build with errors failed (delegated Maven), without errors it was aborted`() {
        assertEquals(BuildResult.FAILED, BuildResult.of(aborted = true, hasErrors = false, problems = listOf(error)))
        assertEquals(BuildResult.ABORTED, BuildResult.of(aborted = true, hasErrors = false, problems = listOf(warning)))
    }

    @Test
    fun `warnings alone do not fail a build`() {
        assertEquals(
            BuildResult.SUCCEEDED,
            BuildResult.of(aborted = false, hasErrors = false, problems = listOf(warning)),
        )
    }
}
