package com.github.sinlordep.mcpcompanion.yield

import com.github.sinlordep.mcpcompanion.testing.FakePluginClassLoader
import com.github.sinlordep.mcpcompanion.testing.ThirdPartyProbeToolset
import com.github.sinlordep.mcpcompanion.testing.fakePluginDescriptor
import com.github.sinlordep.mcpcompanion.testing.thirdPartyDescriptor
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.intellij.mcpserver.McpToolset
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PluginClassLoaderOwnerGatewayTest : BasePlatformTestCase() {

    private val gateway = PluginClassLoaderOwnerGateway()

    private fun ownerOf(id: String, vendor: String?, bundled: Boolean, organization: String? = null) =
        PluginClassLoaderOwnerGateway.ownerOf(fakePluginDescriptor(id, vendor, bundled, organization))

    fun testAClassWithoutPluginBelongsToTheIdeAndIsOfficial() {
        assertEquals(ToolsetOwner.Platform, gateway.ownerOf(ThirdPartyProbeToolset::class.java))
        assertEquals(ToolsetOwner.Platform, PluginClassLoaderOwnerGateway.ownerOf(null))
    }

    /** Whatever loads them in this test IDE, the server's own toolsets are official. */
    fun testTheMcpServersOwnToolsetsAreOfficial() {
        val official = McpToolset.EP.extensionList.filterNot { it is CompanionToolset }
        assertNotEmpty(official)
        assertTrue(official.all { gateway.ownerOf(it.javaClass).isOfficial })
    }

    fun testAClassOfAThirdPartyPluginIsNotOfficial() {
        val type = FakePluginClassLoader(thirdPartyDescriptor(), setOf(ThirdPartyProbeToolset::class.java.name))
            .load(ThirdPartyProbeToolset::class.java)
        assertEquals(ToolsetOwner.Plugin(bundled = false, developedByJetBrains = false), gateway.ownerOf(type))
        assertFalse(gateway.ownerOf(type).isOfficial)
    }

    fun testAJetBrainsPluginFromMarketplaceIsOfficial() {
        val owner = ownerOf("com.jetbrains.example", vendor = "JetBrains", bundled = false)
        assertEquals(ToolsetOwner.Plugin(bundled = false, developedByJetBrains = true), owner)
        assertTrue(owner.isOfficial)
        assertTrue(ownerOf("com.jetbrains.example", vendor = "JetBrains s.r.o.", bundled = false).isOfficial)
        val byOrganization = ownerOf("com.jetbrains.example", null, bundled = false, organization = "JetBrains")
        assertTrue(byOrganization.isOfficial)
    }

    /** An updated bundled plugin lives outside the IDE and no longer counts as bundled; its vendor still counts. */
    fun testAnUpdatedBundledJetBrainsPluginIsOfficial() {
        assertTrue(ownerOf("com.intellij.mcpServer", vendor = "JetBrains", bundled = false).isOfficial)
    }

    fun testABundledPluginIsOfficialWhoeverDevelopsIt() {
        assertEquals(
            ToolsetOwner.Plugin(bundled = true, developedByJetBrains = false),
            ownerOf("org.example.partner", vendor = "Partner", bundled = true),
        )
        assertTrue(ownerOf("org.example.partner", vendor = "Partner", bundled = true).isOfficial)
    }

    /** The platform's own descriptor has the core id and needs no vendor. */
    fun testThePlatformDescriptorIsOfficial() {
        assertTrue(ownerOf("com.intellij", vendor = null, bundled = false).isOfficial)
    }

    fun testAThirdPartyPluginIsNotOfficial() {
        assertFalse(ownerOf("io.example.mcp", vendor = "Example Vendor", bundled = false).isOfficial)
        assertFalse(ownerOf("io.example.mcp", vendor = null, bundled = false).isOfficial)
    }
}
