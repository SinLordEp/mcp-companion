package com.github.sinlordep.mcpcompanion.sync.ide

import com.github.sinlordep.mcpcompanion.sync.BuildSystemSync
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * The build systems one family of IDE support can sync. Registered from optional descriptors: the platform's
 * external-system support (Gradle and any other external system, `mcp-companion-external-system.xml`) and the Maven
 * plugin (`mcp-companion-maven.xml`). Without them the extension point is empty and `mc_sync` reports no build
 * systems.
 */
interface BuildSystemSyncProvider {

    fun buildSystems(project: Project): List<BuildSystemSync>

    companion object {
        val EP: ExtensionPointName<BuildSystemSyncProvider> =
            ExtensionPointName("com.github.sinlordep.mcpcompanion.buildSystemSync")
    }
}
