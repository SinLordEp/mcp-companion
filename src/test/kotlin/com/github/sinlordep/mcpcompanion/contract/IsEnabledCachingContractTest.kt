package com.github.sinlordep.mcpcompanion.contract

import com.github.sinlordep.mcpcompanion.testing.FOREIGN_PROBE_TOOL
import com.github.sinlordep.mcpcompanion.testing.ForeignProbeToolset
import com.github.sinlordep.mcpcompanion.testing.SECOND_FOREIGN_PROBE_TOOL
import com.github.sinlordep.mcpcompanion.testing.SecondForeignProbeToolset
import com.github.sinlordep.mcpcompanion.testing.ServedTools
import com.intellij.mcpserver.settings.McpToolDisallowListSettings
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Regression tests of MCP server behaviour this plugin relies on (docs/spike-step0.md, Q1). If one fails after an IDE
 * update, re-check docs/PLAN.md section 3 before "fixing" the test.
 *
 * 1. `McpToolset.isEnabled()` is cached: the server does not poll it.
 * 2. Any `mcpToolset` EP change makes the server re-ask every toolset. Stepping aside depends on this.
 * 3. The official per-tool switch applies to our tools at once. The user-facing on/off switch depends on this.
 */
class IsEnabledCachingContractTest : BasePlatformTestCase() {

    fun testIsEnabledIsCachedNotPolled() {
        val probe = ForeignProbeToolset(enabled = true)
        ServedTools.register(probe, testRootDisposable)
        ServedTools.waitUntil("probe is served") { FOREIGN_PROBE_TOOL in ServedTools.names() }

        probe.enabled = false
        ServedTools.settle()
        assertTrue("isEnabled() is not polled", FOREIGN_PROBE_TOOL in ServedTools.names())
    }

    fun testToolsetExtensionPointChangeReevaluatesIsEnabled() {
        val probe = ForeignProbeToolset(enabled = true)
        ServedTools.register(probe, testRootDisposable)
        ServedTools.waitUntil("probe is served") { FOREIGN_PROBE_TOOL in ServedTools.names() }
        probe.enabled = false

        val second = ServedTools.register(SecondForeignProbeToolset(), testRootDisposable)
        ServedTools.waitUntil("second probe is served") { SECOND_FOREIGN_PROBE_TOOL in ServedTools.names() }
        ServedTools.waitUntil("disabled probe is dropped") { FOREIGN_PROBE_TOOL !in ServedTools.names() }

        probe.enabled = true
        Disposer.dispose(second)
        ServedTools.waitUntil("second probe is gone") { SECOND_FOREIGN_PROBE_TOOL !in ServedTools.names() }
        ServedTools.waitUntil("re-enabled probe is back") { FOREIGN_PROBE_TOOL in ServedTools.names() }
    }

    /** Test code may use `McpToolDisallowListSettings`; main code must not (owner decision, PLAN section 3). */
    fun testOfficialPerToolSwitchHidesOurToolImmediately() {
        val settings = McpToolDisallowListSettings.getInstance()
        val before = settings.toolStates
        try {
            assertTrue("mc_status" in ServedTools.names())
            settings.toolStates = before + ("mc_status" to McpToolDisallowListSettings.ToolState(enabled = false))
            assertFalse("mc_status" in ServedTools.names())
        } finally {
            settings.toolStates = before
        }
        assertTrue("mc_status" in ServedTools.names())
    }
}
