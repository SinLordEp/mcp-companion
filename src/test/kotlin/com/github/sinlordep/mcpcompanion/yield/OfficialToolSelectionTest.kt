package com.github.sinlordep.mcpcompanion.yield

import com.github.sinlordep.mcpcompanion.testing.COMPANION_PROBE_TOOL
import com.github.sinlordep.mcpcompanion.testing.CompanionProbeToolset
import com.github.sinlordep.mcpcompanion.testing.FOREIGN_PROBE_TOOL
import com.github.sinlordep.mcpcompanion.testing.ForeignProbeToolset
import com.github.sinlordep.mcpcompanion.testing.SECOND_FOREIGN_PROBE_TOOL
import com.github.sinlordep.mcpcompanion.testing.SecondForeignProbeToolset
import com.github.sinlordep.mcpcompanion.testing.THIRD_PARTY_PROBE_TOOL
import com.github.sinlordep.mcpcompanion.testing.ThirdPartyProbeToolset
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which toolsets' tools count as official, and what that means for the yield decision. The owner of each toolset
 * class comes from a fake [ToolsetOwnerGateway]; no IDE runs.
 */
class OfficialToolSelectionTest {

    private val equivalents = mapOf(
        "by-platform" to setOf(FOREIGN_PROBE_TOOL),
        "by-jetbrains-plugin" to setOf(SECOND_FOREIGN_PROBE_TOOL),
        "by-third-party" to setOf(THIRD_PARTY_PROBE_TOOL),
        "by-ours" to setOf(COMPANION_PROBE_TOOL),
    )
    private val catalog = EquivalenceCatalog(equivalents)

    private fun gateway(owners: Map<Class<*>, ToolsetOwner>) = ExtensionPointOfficialToolGateway(
        isOwn = { it is CompanionToolset },
        owners = { type -> owners.getValue(type) },
        reader = McpToolNameReader(),
        toolsets = {
            listOf(
                ForeignProbeToolset(),
                SecondForeignProbeToolset(),
                ThirdPartyProbeToolset(),
                CompanionProbeToolset(),
            )
        },
    )

    private val usualOwners: Map<Class<*>, ToolsetOwner> = mapOf(
        ForeignProbeToolset::class.java to ToolsetOwner.Platform,
        SecondForeignProbeToolset::class.java to ToolsetOwner.Plugin(bundled = false, developedByJetBrains = true),
        ThirdPartyProbeToolset::class.java to ToolsetOwner.Plugin(bundled = false, developedByJetBrains = false),
        CompanionProbeToolset::class.java to ToolsetOwner.Plugin(bundled = false, developedByJetBrains = false),
    )

    @Test
    fun `only tools of the IDE and of JetBrains plugins are official, and ours are ignored`() {
        assertEquals(setOf(FOREIGN_PROBE_TOOL, SECOND_FOREIGN_PROBE_TOOL), gateway(usualOwners).officialToolNames())
    }

    @Test
    fun `a toolset yields to an official tool but not to a third-party tool of a catalog name`() {
        val states = YieldDecider(gateway(usualOwners), OfficialEquivalentPresent(catalog)).statesOf(equivalents.keys)
        assertEquals(
            mapOf(
                "by-platform" to ToolsetState.Yielded(setOf(FOREIGN_PROBE_TOOL)),
                "by-jetbrains-plugin" to ToolsetState.Yielded(setOf(SECOND_FOREIGN_PROBE_TOOL)),
                "by-third-party" to ToolsetState.Available,
                "by-ours" to ToolsetState.Available,
            ),
            states,
        )
    }

    @Test
    fun `a bundled plugin of another vendor is official`() {
        val owners = usualOwners +
            (ThirdPartyProbeToolset::class.java to ToolsetOwner.Plugin(bundled = true, developedByJetBrains = false))
        val decider = YieldDecider(gateway(owners), OfficialEquivalentPresent(catalog))
        assertEquals(ToolsetState.Yielded(setOf(THIRD_PARTY_PROBE_TOOL)), decider.stateOf("by-third-party"))
    }
}
