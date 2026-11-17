package com.github.sinlordep.mcpcompanion.common.build

import com.github.sinlordep.mcpcompanion.common.build.BuildAttribution.ExecutionFacts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which executions and compilations a build reads its messages from. */
class BuildAttributionTest {

    private val delegatedBuild = ExecutionFacts(sameProject = true, buildToolTask = true, listedConfiguration = false)

    @Test
    fun `the unlisted build tool task of this project belongs to the build`() {
        assertTrue(BuildAttribution.executionBelongs(delegatedBuild))
    }

    @Test
    fun `a run configuration started meanwhile does not, by the user or mc_run, Gradle task or application`() {
        // A Gradle task run from the Gradle tool window or a listed configuration: a build tool task, but listed.
        assertFalse(BuildAttribution.executionBelongs(delegatedBuild.copy(listedConfiguration = true)))
        // An application or test run delegated to Gradle keeps its listed configuration.
        assertFalse(
            BuildAttribution.executionBelongs(delegatedBuild.copy(buildToolTask = false, listedConfiguration = true)),
        )
        // Any other process that is not a build tool's task.
        assertFalse(BuildAttribution.executionBelongs(delegatedBuild.copy(buildToolTask = false)))
    }

    @Test
    fun `another project's build does not`() {
        assertFalse(BuildAttribution.executionBelongs(delegatedBuild.copy(sameProject = false)))
    }

    @Test
    fun `a compilation belongs only with this build's session id`() {
        val session = Any()
        assertTrue(BuildAttribution.compilationBelongs(session, session))
        assertFalse(BuildAttribution.compilationBelongs(Any(), session))
        assertFalse(
            "a build without session id (e.g. the IDE's own make)",
            BuildAttribution.compilationBelongs(null, session),
        )
    }
}
