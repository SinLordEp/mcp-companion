package com.github.sinlordep.mcpcompanion.settings

import com.intellij.ide.DataManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.options.ex.Settings
import java.awt.Component

/**
 * The bundled MCP Server's settings page where users switch individual tools on and off.
 *
 * Navigates within the open Settings dialog when there is one, otherwise opens Settings on that page.
 */
class ToolFilterPage(private val configurableId: String = DEFAULT_CONFIGURABLE_ID) {

    /**
     * The page's display name in the current IDE language (in 2026.3 "Exposed Tools"), or null when this IDE has no
     * such page, e.g. because a future MCP Server renamed it.
     */
    fun displayName(): String? =
        Configurable.APPLICATION_CONFIGURABLE.extensionList.firstOrNull { it.id == configurableId }?.getDisplayName()

    fun isAvailable(): Boolean = displayName() != null

    fun open(from: Component) {
        val settings = Settings.KEY.getData(DataManager.getInstance().getDataContext(from))
        val page = settings?.find(configurableId)
        if (settings != null && page != null) {
            settings.select(page)
        } else {
            ShowSettingsUtil.getInstance().showSettingsDialog(
                null,
                { (it as? SearchableConfigurable)?.id == configurableId },
                null,
            )
        }
    }

    companion object {
        /** `McpToolFilterConfigurable` of the bundled MCP Server plugin, 2026.3. */
        const val DEFAULT_CONFIGURABLE_ID: String = "com.intellij.mcpserver.settings.filter"
    }
}
