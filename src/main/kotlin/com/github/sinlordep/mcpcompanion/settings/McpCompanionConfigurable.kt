package com.github.sinlordep.mcpcompanion.settings

import com.github.sinlordep.mcpcompanion.McpCompanionBundle
import com.github.sinlordep.mcpcompanion.common.consent.ConfirmationSetting
import com.github.sinlordep.mcpcompanion.common.consent.ide.BraveModeSetting
import com.github.sinlordep.mcpcompanion.guide.AgentGuide
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolsetStates
import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import java.awt.Component
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * Settings | Tools | MCP Companion: an overview of our toolsets and their state, plus a way to the bundled
 * MCP Server's tool page, where tools are switched on and off. The one editable setting is whether the IDE asks before
 * MCP clients start run configurations ([ConfirmationSetting], the MCP Server's brave mode); it is bound to the panel,
 * so apply, reset and the modified state come from the Kotlin UI DSL. The page also hands out the [AgentGuide] for
 * MCP clients' instruction files.
 */
class McpCompanionConfigurable(
    private val toolFilterPage: ToolFilterPage = ToolFilterPage(),
    private val confirmation: ConfirmationSetting = BraveModeSetting(),
    private val guide: AgentGuide = AgentGuide(),
) : BoundSearchableConfigurable(McpCompanionBundle.message("settings.display.name"), "", ID) {

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
        group(McpCompanionBundle.message("settings.launch.group")) {
            row {
                checkBox(McpCompanionBundle.message("consent.without.confirmation"))
                    .bindSelected({ !confirmation.asksBeforeLaunch }, { confirmation.asksBeforeLaunch = !it })
                    // Wrapped to the page's width: a fixed line length would widen the page beyond the intro text.
                    .comment(McpCompanionBundle.message("settings.launch.comment"), MAX_LINE_LENGTH_WORD_WRAP)
            }
        }
        group(McpCompanionBundle.message("settings.guide.group")) {
            row {
                lateinit var copied: Cell<JLabel>
                button(McpCompanionBundle.message("settings.guide.copy")) {
                    CopyPasteManager.getInstance().setContents(StringSelection(guide.text()))
                    copied.visible(true)
                }.comment(McpCompanionBundle.message("settings.guide.comment"), MAX_LINE_LENGTH_WORD_WRAP)
                copied = label(McpCompanionBundle.message("settings.guide.copied"))
                    .applyToComponent { icon = AllIcons.General.InspectionsOK }
                    .visible(false)
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
