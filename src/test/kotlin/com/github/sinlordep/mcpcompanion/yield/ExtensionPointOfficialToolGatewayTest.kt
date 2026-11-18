package com.github.sinlordep.mcpcompanion.yield

import com.github.sinlordep.mcpcompanion.testing.COMPANION_PROBE_TOOL
import com.github.sinlordep.mcpcompanion.testing.CompanionProbeToolset
import com.github.sinlordep.mcpcompanion.testing.FOREIGN_PROBE_TOOL
import com.github.sinlordep.mcpcompanion.testing.ForeignProbeToolset
import com.github.sinlordep.mcpcompanion.testing.SECOND_FOREIGN_PROBE_TOOL
import com.github.sinlordep.mcpcompanion.testing.SecondForeignProbeToolset
import com.github.sinlordep.mcpcompanion.testing.ServedTools
import com.github.sinlordep.mcpcompanion.testing.THIRD_PARTY_PROBE_TOOL
import com.github.sinlordep.mcpcompanion.testing.fakePluginDescriptor
import com.github.sinlordep.mcpcompanion.testing.thirdPartyToolset
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.intellij.mcpserver.McpToolset
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The gateway on the real `mcpToolset` extension point. Test classes belong to no plugin, so the probe toolsets count
 * as the IDE's own; [thirdPartyToolset] loads one as a class of a plugin.
 */
class ExtensionPointOfficialToolGatewayTest : BasePlatformTestCase() {

    private val gateway = ExtensionPointOfficialToolGateway { it is CompanionToolset }

    fun testFindsTheIdesToolsAndExcludesOurs() {
        val names = gateway.officialToolNames()
        assertContainsElements(names, "get_file_problems", "search_symbol", "rename_refactoring")
        assertFalse("our own tools are excluded", names.any { it.startsWith("mc_") })
    }

    /** A third-party plugin's tool is served by the server like any other, but it is not official. */
    fun testToolsOfAThirdPartyPluginAreNotOfficial() {
        val probe = ServedTools.register(thirdPartyToolset(), testRootDisposable)
        ServedTools.waitUntil("the third-party tool is served") { THIRD_PARTY_PROBE_TOOL in ServedTools.names() }

        val names = gateway.officialToolNames()
        assertFalse(THIRD_PARTY_PROBE_TOOL in names)
        assertContainsElements(names, "search_symbol")
        removeAndWait(probe)
    }

    /** A JetBrains plugin installed from Marketplace is not bundled, but it is official. */
    fun testToolsOfAJetBrainsPluginFromMarketplaceAreOfficial() {
        val jetBrainsPlugin = fakePluginDescriptor("com.jetbrains.example", vendor = "JetBrains", bundled = false)
        val probe = ServedTools.register(thirdPartyToolset(jetBrainsPlugin), testRootDisposable)
        ServedTools.waitUntil("the JetBrains tool is served") { THIRD_PARTY_PROBE_TOOL in ServedTools.names() }
        assertTrue(THIRD_PARTY_PROBE_TOOL in gateway.officialToolNames())
        removeAndWait(probe)
    }

    /** Lets the server's recomputation for the removal finish here, not in a later test. */
    private fun removeAndWait(probe: Disposable) {
        Disposer.dispose(probe)
        ServedTools.waitUntil("the probe tool is gone") { THIRD_PARTY_PROBE_TOOL !in ServedTools.names() }
    }

    fun testSeesDisabledToolsets() {
        ServedTools.register(ForeignProbeToolset(enabled = false), testRootDisposable)
        assertTrue(FOREIGN_PROBE_TOOL in gateway.officialToolNames())
    }

    fun testNeverAsksOtherToolsetsWhetherTheyAreEnabled() {
        val probe = ForeignProbeToolset()
        ServedTools.register(probe, testRootDisposable)
        // Registering is an EP change: the server itself re-asks every toolset. Let that finish first.
        ServedTools.waitUntil("the server serves the probe") { FOREIGN_PROBE_TOOL in ServedTools.names() }
        ServedTools.settle()
        val askedByServer = probe.isEnabledCalls.get()

        repeat(3) { gateway.officialToolNames() }
        assertEquals("the gateway asked isEnabled()", askedByServer, probe.isEnabledCalls.get())
    }

    fun testSeesExtensionPointChangesImmediately() {
        val lifetime = ServedTools.register(ForeignProbeToolset(), testRootDisposable)
        assertTrue(FOREIGN_PROBE_TOOL in gateway.officialToolNames())
        Disposer.dispose(lifetime)
        assertFalse(FOREIGN_PROBE_TOOL in gateway.officialToolNames())
    }

    /** A foreign toolset that cannot be reflected is skipped; the rest is still read and yield stays decidable. */
    fun testUnreadableToolsetIsSkippedAndTheYieldDecisionStillWorks() {
        ServedTools.register(ForeignProbeToolset(), testRootDisposable)
        ServedTools.register(SecondForeignProbeToolset(), testRootDisposable)
        val failingReader = McpToolNameReader { type ->
            if (type == ForeignProbeToolset::class.java) throw NoClassDefFoundError("x/Missing")
            reflectMcpToolNames(type)
        }
        val tolerant = ExtensionPointOfficialToolGateway(
            isOwn = { it is CompanionToolset },
            owners = PluginClassLoaderOwnerGateway(),
            reader = failingReader,
            toolsets = { McpToolset.EP.extensionList },
        )

        val names = tolerant.officialToolNames()
        assertFalse(FOREIGN_PROBE_TOOL in names)
        assertContainsElements(names, SECOND_FOREIGN_PROBE_TOOL, "search_symbol")

        val catalog = EquivalenceCatalog.parse("a = $SECOND_FOREIGN_PROBE_TOOL\nb = $FOREIGN_PROBE_TOOL")
        val states = YieldDecider(tolerant, OfficialEquivalentPresent(catalog)).statesOf(listOf("a", "b"))
        assertEquals(ToolsetState.Yielded(setOf(SECOND_FOREIGN_PROBE_TOOL)), states.getValue("a"))
        assertEquals(ToolsetState.Available, states.getValue("b"))
    }

    /**
     * Re-entrancy: the server calls our `isEnabled()` while it walks the `mcpToolset` EP, and our `isEnabled()` walks
     * the same EP through this gateway. The recomputation must finish, ask us, and serve our tool.
     */
    fun testServerRecomputesWhileOurIsEnabledConsultsTheGateway() {
        val ours = CompanionProbeToolset()
        ServedTools.register(ours, testRootDisposable)
        ServedTools.waitUntil("our probe is served") { COMPANION_PROBE_TOOL in ServedTools.names() }
        assertTrue("the server asked our isEnabled()", ours.isEnabledCalls.get() > 0)
        assertTrue("other tools are still served", "search_symbol" in ServedTools.names())
    }
}
