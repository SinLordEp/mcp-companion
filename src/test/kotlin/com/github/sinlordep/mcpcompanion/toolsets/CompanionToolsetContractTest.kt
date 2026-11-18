package com.github.sinlordep.mcpcompanion.toolsets

import com.github.sinlordep.mcpcompanion.testing.SecondForeignProbeToolset
import com.github.sinlordep.mcpcompanion.testing.ServedTools
import com.github.sinlordep.mcpcompanion.testing.THIRD_PARTY_PROBE_TOOL
import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.github.sinlordep.mcpcompanion.testing.thirdPartyToolset
import com.github.sinlordep.mcpcompanion.yield.EquivalenceCatalog
import com.github.sinlordep.mcpcompanion.yield.ExtensionPointOfficialToolGateway
import com.github.sinlordep.mcpcompanion.yield.OfficialEquivalentPresent
import com.github.sinlordep.mcpcompanion.yield.ToolsetState
import com.github.sinlordep.mcpcompanion.yield.YieldDecider
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.McpToolset
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        // When this is the first test of the JVM, the server may still be recomputing after the IDE start.
        ServedTools.waitUntil("mc_status is served") { "mc_status" in ServedTools.names() }
        ServedTools.settle()
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

    /**
     * A third-party plugin's tool named like a catalog entry is no reason to step aside: with `status = <that tool>`,
     * `mc_status` called through the server and the data of the settings page both report `status` available, and
     * the server keeps serving it after the EP change that brought the third-party tool.
     */
    fun testToolsetDoesNotYieldToAThirdPartyToolNamedInTheCatalog() {
        val serviceLifetime = Disposer.newDisposable(testRootDisposable, "third-party catalog")
        ApplicationManager.getApplication().replaceService(
            CompanionToolsetStates::class.java,
            statesWithCatalog("status = $THIRD_PARTY_PROBE_TOOL"),
            serviceLifetime,
        )
        val probe = ServedTools.register(thirdPartyToolset(), testRootDisposable)
        ServedTools.waitUntil("the third-party tool is served") { THIRD_PARTY_PROBE_TOOL in ServedTools.names() }
        ServedTools.settle()

        assertTrue(ourToolsets().filterIsInstance<StatusToolset>().single().isEnabled())
        assertEquals(
            ToolsetState.Available,
            CompanionToolsetStates.getInstance().overview().single { it.id == StatusToolset.ID }.state,
        )
        assertTrue("still served after the EP change", "mc_status" in ServedTools.names())

        val tools = ToolCalls(project)
        try {
            val result = tools.callJson("mc_status", JsonObject(emptyMap()))
            val text = result.content.filterIsInstance<McpToolCallResultContent.Text>().single().text
            val status = Json.parseToJsonElement(text).jsonObject.getValue("toolsets").jsonArray
                .map { it.jsonObject }
                .single { it.getValue("id").jsonPrimitive.content == StatusToolset.ID }
            assertEquals("available", status.getValue("state").jsonPrimitive.content)
            assertTrue(status.getValue("officialTools").jsonArray.isEmpty())
        } finally {
            tools.dispose()
        }
        // Let the server's recomputation for the removal finish here, not in a later test.
        Disposer.dispose(probe)
        ServedTools.waitUntil("the third-party tool is gone") { THIRD_PARTY_PROBE_TOOL !in ServedTools.names() }
    }

    /** With a third-party and an official tool in one catalog entry, only the official one counts and is reported. */
    fun testOnlyTheOfficialToolOfACatalogEntryIsAReasonToYield() {
        val serviceLifetime = Disposer.newDisposable(testRootDisposable, "mixed catalog")
        ApplicationManager.getApplication().replaceService(
            CompanionToolsetStates::class.java,
            statesWithCatalog("status = $THIRD_PARTY_PROBE_TOOL, search_symbol"),
            serviceLifetime,
        )
        val probe = ServedTools.register(thirdPartyToolset(), testRootDisposable)
        assertEquals(
            ToolsetState.Yielded(setOf("search_symbol")),
            CompanionToolsetStates.getInstance().overview().single { it.id == StatusToolset.ID }.state,
        )
        // Let the recomputation of that EP change finish before the original states return.
        ServedTools.waitUntil("mc_status is dropped") { "mc_status" !in ServedTools.names() }

        Disposer.dispose(serviceLifetime)
        Disposer.dispose(probe)
        ServedTools.waitUntil("mc_status is back") { "mc_status" in ServedTools.names() }
    }

    private fun statesWithCatalog(catalog: String) = CompanionToolsetStates(
        YieldDecider(
            ExtensionPointOfficialToolGateway { it is CompanionToolset },
            OfficialEquivalentPresent(EquivalenceCatalog.parse(catalog)),
        ),
    )
}
