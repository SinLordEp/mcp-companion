package com.github.sinlordep.mcpcompanion.status

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.project.Project

/**
 * Gathers [IdeStatus] from the platform. The only place in `status/` that touches IntelliJ APIs.
 *
 * The plugin descriptor is passed in rather than looked up: in 2026.3 `PluginManagerCore.getPlugin` and
 * `PluginManager.getPluginByClass` are `@ApiStatus.Internal`.
 */
class IdeStatusCollector(private val plugin: PluginDescriptor) {

    fun collect(project: Project): IdeStatus = IdeStatus(
        pluginVersion = plugin.version,
        ideBuild = ApplicationInfo.getInstance().build.asString(),
        projectName = project.name,
    )
}
