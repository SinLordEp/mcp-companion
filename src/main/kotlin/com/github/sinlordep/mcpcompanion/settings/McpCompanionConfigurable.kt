package com.github.sinlordep.mcpcompanion.settings

import com.github.sinlordep.mcpcompanion.McpCompanionBundle
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolsetStates
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import java.awt.Component
import javax.swing.JComponent

/**
 * Settings | Tools | MCP Companion: a read-only overview of our toolsets and their state, plus a way to the bundled
 * MCP Server's tool page, where tools are switched on and off. Nothing here is editable, so there is nothing to apply.
 */
class McpCompanionConfigurable(private val toolFilterPage: ToolFilterPage = ToolFilterPage()) :
    BoundSearchableConfigurable(McpCompanionBundle.message("settings.display.name"), "", ID) {

    override fun createPanel(): DialogPanel = panel {
        row { text(McpCompanionBundle.message("settings.intro")) }
        row { cell(toolsetTable()).align(AlignX.FILL) }
        row { text(McpCompanionBundle.message("settings.switching")) }
        row {
            val pageName = toolFilterPage.displayName()
            if (pageName != null) {
                link(McpCompanionBundle.message("settings.open.tool.page", pageName)) { event ->
                    toolFilterPage.open(event.source as Component)
                }
            } else {
                comment(McpCompanionBundle.message("settings.tool.page.missing"))
            }
        }
    }

    private fun toolsetTable(): JComponent {
        val table = TableView(ToolsetTableModel(CompanionToolsetStates.getInstance().overview()))
        table.emptyText.text = McpCompanionBundle.message("settings.table.empty")
        table.setShowGrid(false)
        // Every toolset without scrolling (the page has room); a scroll bar only beyond MAX_VISIBLE_ROWS.
        table.visibleRowCount = table.rowCount.coerceIn(1, MAX_VISIBLE_ROWS)
        return ScrollPaneFactory.createScrollPane(table)
    }

    companion object {
        const val ID: String = "com.github.sinlordep.mcpcompanion.settings"
        private const val MAX_VISIBLE_ROWS = 12
    }
}
