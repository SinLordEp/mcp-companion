package com.github.sinlordep.mcpcompanion.build.ide

import com.github.sinlordep.mcpcompanion.build.ModuleBuilder
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Builds modules for `mc_build`. Registered from `mcp-companion-java.xml`: the IDE's build path for JVM modules
 * (`ProjectTaskManager` with the IDE's compiler or the Gradle/Maven delegation) and its compiler messages need the
 * Java plugin. Without it the extension point is empty and `build` answers `UNSUPPORTED`.
 */
interface ModuleBuilderProvider {

    fun builderFor(project: Project): ModuleBuilder

    companion object {
        val EP: ExtensionPointName<ModuleBuilderProvider> =
            ExtensionPointName("com.github.sinlordep.mcpcompanion.moduleBuilder")
    }
}
