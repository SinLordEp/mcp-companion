package com.github.sinlordep.mcpcompanion.testing

import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import java.util.concurrent.atomic.AtomicInteger

/** A foreign toolset with one tool; [enabled] and the [isEnabledCalls] counter are under the test's control. */
class ForeignProbeToolset(@Volatile var enabled: Boolean = true) : McpToolset {
    val isEnabledCalls = AtomicInteger()

    override fun isEnabled(): Boolean = enabled.also { isEnabledCalls.incrementAndGet() }

    override fun isExperimental(): Boolean = false

    @McpTool(name = FOREIGN_PROBE_TOOL)
    @McpDescription(description = "Test probe; never shipped.")
    suspend fun probe(): String = "probe"
}

/** Another foreign toolset, used only to cause a second, independent EP change. */
class SecondForeignProbeToolset : McpToolset {
    override fun isExperimental(): Boolean = false

    @McpTool(name = SECOND_FOREIGN_PROBE_TOOL)
    @McpDescription(description = "Test probe; never shipped.")
    suspend fun secondProbe(): String = "probe"
}

/**
 * A toolset for a third-party plugin; load it through a [FakePluginClassLoader] to make it one. Loaded normally, it
 * belongs to no plugin, like any test class.
 */
class ThirdPartyProbeToolset : McpToolset {
    override fun isExperimental(): Boolean = false

    @McpTool(name = THIRD_PARTY_PROBE_TOOL)
    @McpDescription(description = "Test probe; never shipped.")
    suspend fun thirdPartyProbe(): String = "probe"
}

/** One of "our" toolsets for tests; counts how often the server asks whether it is enabled. */
class CompanionProbeToolset : CompanionToolset {
    val isEnabledCalls = AtomicInteger()

    override val toolsetId: String = COMPANION_PROBE_ID

    override fun isEnabled(): Boolean = super.isEnabled().also { isEnabledCalls.incrementAndGet() }

    override fun displayName(): String = "Companion probe"

    override fun displayDescription(): String = "Test probe; never shipped."

    @McpTool(name = COMPANION_PROBE_TOOL)
    @McpDescription(description = "Test probe; never shipped.")
    suspend fun companionProbe(): String = "probe"
}

const val FOREIGN_PROBE_TOOL = "test_foreign_probe"
const val SECOND_FOREIGN_PROBE_TOOL = "test_second_foreign_probe"
const val THIRD_PARTY_PROBE_TOOL = "test_third_party_probe"
const val COMPANION_PROBE_ID = "test-companion-probe"
const val COMPANION_PROBE_TOOL = "mc_test_companion_probe"
