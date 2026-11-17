package com.github.sinlordep.mcpcompanion.settings

import com.github.sinlordep.mcpcompanion.common.consent.ConfirmationSetting
import com.github.sinlordep.mcpcompanion.common.consent.ide.BraveModeSetting
import com.github.sinlordep.mcpcompanion.toolsets.ToolsetOverview
import com.github.sinlordep.mcpcompanion.yield.ToolsetState
import com.intellij.mcpserver.settings.McpServerSettings
import com.intellij.openapi.options.Configurable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
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
        assertEquals(
            listOf(
                "MCP Companion: Status",
                "MCP Companion: Find Usages",
                "MCP Companion: Find Implementations",
                "MCP Companion: Type Hierarchy",
                "MCP Companion: Safe Delete",
                "MCP Companion: Run Control",
                "MCP Companion: Modules",
                "MCP Companion: Build Sync",
                "MCP Companion: Diagnostics",
                "MCP Companion: Module Build",
            ),
            (0 until table.rowCount).map { table.getValueAt(it, 0) },
        )
        assertEquals(setOf("Available"), (0 until table.rowCount).map { table.getValueAt(it, 1) }.toSet())
        assertFalse(table.isCellEditable(0, 1))
        assertEquals("every toolset is visible without scrolling", table.rowCount, table.visibleRowCount)
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

    fun testLaunchConfirmationIsEditableWithApplyAndReset() {
        val setting = object : ConfirmationSetting {
            override var asksBeforeLaunch = true
        }
        val configurable = McpCompanionConfigurable(confirmation = setting)
        withComponent(configurable) { component ->
            val box = UIUtil.findComponentsOfType(component, JBCheckBox::class.java)
                .single { it.text == "Run shell commands or run configurations without confirmation (brave mode)" }
            assertFalse("asking is shown as unticked", box.isSelected)
            assertFalse(configurable.isModified)

            box.isSelected = true
            assertTrue(configurable.isModified)
            assertTrue("nothing changes before apply", setting.asksBeforeLaunch)
            configurable.apply()
            assertFalse(setting.asksBeforeLaunch)
            assertFalse(configurable.isModified)

            setting.asksBeforeLaunch = true
            configurable.reset()
            assertFalse(box.isSelected)
            box.isSelected = true
            configurable.reset()
            assertFalse("reset discards the change", box.isSelected)
            assertTrue(setting.asksBeforeLaunch)
        }
    }

    fun testTheConfirmationSettingIsTheMcpServersBraveMode() {
        val settings = McpServerSettings.getInstance()
        val before = settings.enableBraveMode
        try {
            settings.enableBraveMode = false
            assertTrue(BraveModeSetting().asksBeforeLaunch)
            BraveModeSetting().asksBeforeLaunch = false
            assertTrue(settings.enableBraveMode)
            settings.enableBraveMode = false
            assertTrue("read on every call", BraveModeSetting().asksBeforeLaunch)
        } finally {
            settings.enableBraveMode = before
        }
    }

    fun testYieldedStateNamesTheOfficialTools() {
        val overview = ToolsetOverview("x", "X", "d", ToolsetState.Yielded(setOf("b_tool", "a_tool")))
        assertEquals("Provided by official a_tool, b_tool", ToolsetTableModel.stateText(overview.state))
    }
}
