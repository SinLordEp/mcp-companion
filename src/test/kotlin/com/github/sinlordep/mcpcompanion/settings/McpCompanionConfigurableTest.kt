package com.github.sinlordep.mcpcompanion.settings

import com.github.sinlordep.mcpcompanion.toolsets.ToolsetOverview
import com.github.sinlordep.mcpcompanion.yield.ToolsetState
import com.intellij.openapi.options.Configurable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.table.TableView
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent

class McpCompanionConfigurableTest : BasePlatformTestCase() {

    private fun withComponent(configurable: McpCompanionConfigurable, check: (JComponent) -> Unit) {
        try {
            check(checkNotNull(configurable.createComponent()))
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testIsRegisteredUnderTools() {
        val ep = Configurable.APPLICATION_CONFIGURABLE.extensionList.single { it.id == McpCompanionConfigurable.ID }
        assertEquals("tools", ep.parentId)
    }

    fun testShowsEachToolsetWithItsStateAndIsReadOnly() = withComponent(McpCompanionConfigurable()) { component ->
        val table = UIUtil.findComponentOfType(component, TableView::class.java)!!
        assertEquals(1, table.rowCount)
        assertEquals("MCP Companion: Status", table.getValueAt(0, 0))
        assertEquals("Available", table.getValueAt(0, 1))
        assertFalse(table.isCellEditable(0, 1))
        assertFalse(McpCompanionConfigurable().isModified)
    }

    fun testLinksToTheOfficialToolPage() = withComponent(McpCompanionConfigurable()) { component ->
        assertEquals("Exposed Tools", ToolFilterPage().displayName())
        assertEquals("Open Exposed Tools", UIUtil.findComponentOfType(component, ActionLink::class.java)?.text)
    }

    fun testDegradesWhenTheOfficialToolPageIsMissing() {
        val missing = ToolFilterPage("no.such.configurable")
        assertFalse(missing.isAvailable())
        withComponent(McpCompanionConfigurable(missing)) { component ->
            assertNull(UIUtil.findComponentOfType(component, ActionLink::class.java))
        }
    }

    fun testYieldedStateNamesTheOfficialTools() {
        val overview = ToolsetOverview("x", "X", "d", ToolsetState.Yielded(setOf("b_tool", "a_tool")))
        assertEquals("Provided by official a_tool, b_tool", ToolsetTableModel.stateText(overview.state))
    }
}
