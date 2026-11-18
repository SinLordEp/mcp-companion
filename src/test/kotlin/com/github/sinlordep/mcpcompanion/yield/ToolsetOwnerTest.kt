package com.github.sinlordep.mcpcompanion.yield

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsetOwnerTest {

    @Test
    fun `the IDE itself is official`() {
        assertTrue(ToolsetOwner.Platform.isOfficial)
    }

    @Test
    fun `a bundled plugin is official, whoever develops it`() {
        assertTrue(ToolsetOwner.Plugin(bundled = true, developedByJetBrains = true).isOfficial)
        assertTrue(ToolsetOwner.Plugin(bundled = true, developedByJetBrains = false).isOfficial)
    }

    @Test
    fun `a JetBrains plugin from Marketplace or an updated bundled one is official`() {
        assertTrue(ToolsetOwner.Plugin(bundled = false, developedByJetBrains = true).isOfficial)
    }

    @Test
    fun `a third-party plugin is not official`() {
        assertFalse(ToolsetOwner.Plugin(bundled = false, developedByJetBrains = false).isOfficial)
    }
}
