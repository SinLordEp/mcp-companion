package com.github.sinlordep.mcpcompanion.modules.externalsystem

import com.github.sinlordep.mcpcompanion.modules.ide.ModuleFactsProvider
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.module.Module

/**
 * The build system that imported a module: the external-system id the IDE stores on it (Gradle: `GRADLE`), or the
 * Maven flag for modules the Maven plugin imported. Registered in `mcp-companion-external-system.xml`, which loads
 * only where the platform's external-system support exists.
 */
class ExternalSystemModuleFacts : ModuleFactsProvider {

    override fun buildSystem(module: Module): String? {
        val properties = ExternalSystemModulePropertyManager.getInstance(module)
        return when {
            properties.isMavenized() -> MAVEN
            else -> properties.getExternalSystemId()?.lowercase()
        }
    }

    private companion object {
        const val MAVEN = "maven"
    }
}
