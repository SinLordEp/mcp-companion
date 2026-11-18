package com.github.sinlordep.mcpcompanion.toolsets

import com.github.sinlordep.mcpcompanion.testing.SecondForeignProbeToolset
import com.github.sinlordep.mcpcompanion.testing.ServedTools
import com.github.sinlordep.mcpcompanion.yield.EquivalenceCatalog
import com.github.sinlordep.mcpcompanion.yield.ExtensionPointForeignToolGateway
import com.github.sinlordep.mcpcompanion.yield.OfficialEquivalentPresent
import com.github.sinlordep.mcpcompanion.yield.ToolsetState
import com.github.sinlordep.mcpcompanion.yield.YieldDecider
import com.intellij.mcpserver.McpToolset
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService

class CompanionToolsetContractTest : BasePlatformTestCase() {

    private fun ourToolsets(): List<CompanionToolset> = McpToolset.EP.extensionList.filterIsInstance<CompanionToolset>()

    fun testEveryToolsetHasAUniqueIdAndBundleTexts() {
        val toolsets = ourToolsets()
        assertNotEmpty(toolsets)
        assertEquals("ids are unique", toolsets.size, toolsets.map { it.toolsetId }.toSet().size)
        for (toolset in toolsets) {
            for (text in listOf(toolset.shortName, toolset.summary)) {
                assertFalse("${toolset.toolsetId}: '$text'", text.isBlank() || text.startsWith("!"))
            }
            assertEquals("MCP Companion: ${toolset.shortName}", toolset.displayName())
            assertNull("no group description on Exposed Tools", toolset.displayDescription())
            assertFalse(toolset.toolsetId, toolset.isExperimental())
        }
    }

    fun testWithTheShippedCatalogEveryToolsetIsAvailable() {
        assertTrue(ourToolsets().all { it.isEnabled() })
        assertTrue(CompanionToolsetStates.getInstance().overview().all { it.state == ToolsetState.Available })
    }

    /**
     * Stepping aside end to end at platform level: with a catalog entry `status = search_symbol` (an official tool
     * that exists), `mc_status` reports itself yielded and the server drops it at the next `mcpToolset` EP change.
     */
    fun testToolsetYieldsToAnOfficialEquivalentAndTheServerDropsIt() {
        val serviceLifetime = Disposer.newDisposable(testRootDisposable, "yielding catalog")
        ApplicationManager.getApplication().replaceService(
            CompanionToolsetStates::class.java,
            statesWithCatalog("status = search_symbol"),
            serviceLifetime,
        )
        val status = ourToolsets().filterIsInstance<StatusToolset>().single()
        assertFalse(status.isEnabled())
        assertEquals(
            ToolsetState.Yielded(setOf("search_symbol")),
            CompanionToolsetStates.getInstance().overview().single { it.id == StatusToolset.ID }.state,
        )

        assertTrue("cached until the EP changes", "mc_status" in ServedTools.names())
        val probe = ServedTools.register(SecondForeignProbeToolset(), testRootDisposable)
        ServedTools.waitUntil("mc_status is dropped") { "mc_status" !in ServedTools.names() }

        Disposer.dispose(serviceLifetime)
        Disposer.dispose(probe)
        ServedTools.waitUntil("mc_status is back") { "mc_status" in ServedTools.names() }
    }

    private fun statesWithCatalog(catalog: String) = CompanionToolsetStates(
        YieldDecider(
            ExtensionPointForeignToolGateway { it is CompanionToolset },
            OfficialEquivalentPresent(EquivalenceCatalog.parse(catalog)),
        ),
    )
}
