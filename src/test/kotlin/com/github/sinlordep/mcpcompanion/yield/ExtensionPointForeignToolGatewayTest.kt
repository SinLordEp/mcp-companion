package com.github.sinlordep.mcpcompanion.yield

import com.github.sinlordep.mcpcompanion.testing.COMPANION_PROBE_TOOL
import com.github.sinlordep.mcpcompanion.testing.CompanionProbeToolset
import com.github.sinlordep.mcpcompanion.testing.FOREIGN_PROBE_TOOL
import com.github.sinlordep.mcpcompanion.testing.ForeignProbeToolset
import com.github.sinlordep.mcpcompanion.testing.SECOND_FOREIGN_PROBE_TOOL
import com.github.sinlordep.mcpcompanion.testing.SecondForeignProbeToolset
import com.github.sinlordep.mcpcompanion.testing.ServedTools
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ExtensionPointForeignToolGatewayTest : BasePlatformTestCase() {

    private val gateway = ExtensionPointForeignToolGateway { it is CompanionToolset }

    fun testFindsOfficialToolsAndExcludesOurs() {
        val names = gateway.foreignToolNames()
        assertContainsElements(names, "get_file_problems", "search_symbol", "rename_refactoring")
        assertFalse("our own tools are excluded", names.any { it.startsWith("mc_") })
    }

    fun testSeesDisabledToolsets() {
        ServedTools.register(ForeignProbeToolset(enabled = false), testRootDisposable)
        assertTrue(FOREIGN_PROBE_TOOL in gateway.foreignToolNames())
    }

    fun testNeverAsksOtherToolsetsWhetherTheyAreEnabled() {
        val probe = ForeignProbeToolset()
        ServedTools.register(probe, testRootDisposable)
        // Registering is an EP change: the server itself re-asks every toolset. Let that finish first.
        ServedTools.waitUntil("the server serves the probe") { FOREIGN_PROBE_TOOL in ServedTools.names() }
        ServedTools.settle()
        val askedByServer = probe.isEnabledCalls.get()

        repeat(3) { gateway.foreignToolNames() }
        assertEquals("the gateway asked isEnabled()", askedByServer, probe.isEnabledCalls.get())
    }

    fun testSeesExtensionPointChangesImmediately() {
        val lifetime = ServedTools.register(ForeignProbeToolset(), testRootDisposable)
        assertTrue(FOREIGN_PROBE_TOOL in gateway.foreignToolNames())
        Disposer.dispose(lifetime)
        assertFalse(FOREIGN_PROBE_TOOL in gateway.foreignToolNames())
    }

    /** A foreign toolset that cannot be reflected is skipped; the rest is still read and yield stays decidable. */
    fun testUnreadableToolsetIsSkippedAndTheYieldDecisionStillWorks() {
        ServedTools.register(ForeignProbeToolset(), testRootDisposable)
        ServedTools.register(SecondForeignProbeToolset(), testRootDisposable)
        val failingReader = McpToolNameReader { type ->
            if (type == ForeignProbeToolset::class.java) throw NoClassDefFoundError("x/Missing")
            reflectMcpToolNames(type)
        }
        val tolerant = ExtensionPointForeignToolGateway({ it is CompanionToolset }, failingReader)

        val names = tolerant.foreignToolNames()
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
