package com.github.sinlordep.mcpcompanion.settings

import com.github.sinlordep.mcpcompanion.common.consent.ConfirmationSetting
import com.github.sinlordep.mcpcompanion.common.consent.ide.BraveModeSetting
import com.github.sinlordep.mcpcompanion.guide.AgentGuide
import com.github.sinlordep.mcpcompanion.testing.ServedTools
import com.github.sinlordep.mcpcompanion.testing.THIRD_PARTY_PROBE_TOOL
import com.github.sinlordep.mcpcompanion.testing.thirdPartyToolset
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolsetStates
import com.github.sinlordep.mcpcompanion.toolsets.ToolsetOverview
import com.github.sinlordep.mcpcompanion.yield.EquivalenceCatalog
import com.github.sinlordep.mcpcompanion.yield.ExtensionPointOfficialToolGateway
import com.github.sinlordep.mcpcompanion.yield.OfficialEquivalentPresent
import com.github.sinlordep.mcpcompanion.yield.ToolsetState
import com.github.sinlordep.mcpcompanion.yield.YieldDecider
import com.intellij.mcpserver.settings.McpServerSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.table.TableView
import com.intellij.util.ui.UIUtil
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel

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
                "Status",
                "Find Usages",
                "Find Implementations",
                "Type Hierarchy",
                "Safe Delete",
                "Move Class",
                "Change Signature",
                "Run Control",
                "Modules",
                "Build Sync",
                "Diagnostics",
                "Module Build",
                "Quick Fix",
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

    /** A third-party plugin's tool named in the catalog leaves every row available; an official one does not. */
    fun testThePageShowsAToolsetAvailableDespiteAThirdPartyToolOfACatalogName() {
        val probe = ServedTools.register(thirdPartyToolset(), testRootDisposable)
        ServedTools.waitUntil("the third-party tool is served") { THIRD_PARTY_PROBE_TOOL in ServedTools.names() }
        val states = CompanionToolsetStates(
            YieldDecider(
                ExtensionPointOfficialToolGateway { it is CompanionToolset },
                OfficialEquivalentPresent(EquivalenceCatalog.parse("status = $THIRD_PARTY_PROBE_TOOL")),
            ),
        )
        ApplicationManager.getApplication()
            .replaceService(CompanionToolsetStates::class.java, states, testRootDisposable)

        withComponent(McpCompanionConfigurable()) { component ->
            val table = UIUtil.findComponentOfType(component, TableView::class.java)!!
            assertEquals(
                setOf(ToolsetTableModel.stateText(ToolsetState.Available)),
                (0 until table.rowCount).map { table.getValueAt(it, 1) }.toSet(),
            )
        }
        // Let the server's recomputation for the removal finish here, not in a later test.
        Disposer.dispose(probe)
        ServedTools.waitUntil("the third-party tool is gone") { THIRD_PARTY_PROBE_TOOL !in ServedTools.names() }
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

    fun testCopiesTheAgentGuideAndConfirmsIt() {
        val configurable = McpCompanionConfigurable()
        withComponent(configurable) { component -> copyGuide(component, configurable) }
    }

    private fun copyGuide(component: JComponent, configurable: McpCompanionConfigurable) {
        val clipboard = CopyPasteManager.getInstance()
        clipboard.setContents(StringSelection("before"))
        val copied = UIUtil.findComponentsOfType(component, JLabel::class.java)
            .single { it.text == "Copied to the clipboard" }
        assertFalse("no confirmation before the click", copied.isVisible)

        UIUtil.findComponentsOfType(component, JButton::class.java).single { it.text == "Copy Guide" }.doClick()

        assertEquals(AgentGuide().text(), clipboard.getContents<String>(DataFlavor.stringFlavor))
        assertTrue(copied.isVisible)
        assertFalse("copying changes no setting", configurable.isModified)
    }

    fun testYieldedStateNamesTheOfficialTools() {
        val overview = ToolsetOverview("x", "X: x", "X", "d", ToolsetState.Yielded(setOf("b_tool", "a_tool")))
        assertEquals("Provided by official a_tool, b_tool", ToolsetTableModel.stateText(overview.state))
    }
}
