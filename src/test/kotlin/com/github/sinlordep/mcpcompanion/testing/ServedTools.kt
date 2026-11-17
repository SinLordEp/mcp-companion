package com.github.sinlordep.mcpcompanion.testing

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil

/**
 * Test-only view of what the bundled MCP server serves, and helpers to change the `mcpToolset` extension point.
 * Uses `impl` classes, which is allowed in tests only.
 */
object ServedTools {

    private const val WAIT_SECONDS = 10
    private const val SETTLE_MILLIS = 1_500L

    /** Tool names the server would list (`McpServerService.getMcpTools()`, i.e. after its filters). */
    fun names(): Set<String> = McpServerService.getInstance().getMcpTools().map { it.descriptor.name }.toSet()

    /** Registers [toolset] until the returned disposable (a child of [parent]) is disposed. An EP change. */
    fun register(toolset: McpToolset, parent: Disposable): Disposable {
        val lifetime = Disposer.newDisposable(parent, "test toolset ${toolset.javaClass.simpleName}")
        McpToolset.EP.point.registerExtension(toolset, lifetime)
        return lifetime
    }

    fun waitUntil(what: String, condition: () -> Boolean) {
        PlatformTestUtil.waitWithEventsDispatching("Timed out waiting until $what", condition, WAIT_SECONDS)
    }

    /** Waits long enough for an asynchronous recomputation to have happened, if one were going to happen. */
    fun settle() {
        val until = System.currentTimeMillis() + SETTLE_MILLIS
        PlatformTestUtil.waitWithEventsDispatching("settle", { System.currentTimeMillis() >= until }, WAIT_SECONDS)
    }
}
