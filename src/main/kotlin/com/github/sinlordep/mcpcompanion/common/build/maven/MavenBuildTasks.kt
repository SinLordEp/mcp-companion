package com.github.sinlordep.mcpcompanion.common.build.maven

import com.github.sinlordep.mcpcompanion.common.build.ide.BuildToolTaskProvider
import com.intellij.execution.configurations.RunProfile
import org.jetbrains.idea.maven.execution.MavenRunConfiguration

/** A build delegated to Maven runs as a [MavenRunConfiguration]. */
class MavenBuildTasks : BuildToolTaskProvider {
    override fun isBuildToolTask(profile: RunProfile): Boolean = profile is MavenRunConfiguration
}
