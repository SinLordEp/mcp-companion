package com.github.sinlordep.mcpcompanion.common.build.ide

import com.intellij.execution.configurations.RunProfile
import com.intellij.openapi.extensions.ExtensionPointName

/**
 * Tells whether a run profile is a build tool's task run (how the IDE runs a build delegated to Gradle, Maven or
 * another external system). Registered from the optional descriptors of the external-system support and the Maven
 * plugin; without them nothing counts as a build tool's task, and no delegated build exists either.
 */
interface BuildToolTaskProvider {

    fun isBuildToolTask(profile: RunProfile): Boolean

    companion object {
        val EP: ExtensionPointName<BuildToolTaskProvider> =
            ExtensionPointName("com.github.sinlordep.mcpcompanion.buildToolTask")

        fun isBuildToolTask(profile: RunProfile): Boolean = EP.extensionList.any { it.isBuildToolTask(profile) }
    }
}
