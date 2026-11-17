package com.github.sinlordep.mcpcompanion.toolsets

import com.github.sinlordep.mcpcompanion.status.IdeStatusCollector
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.extensions.PluginAware
import com.intellij.openapi.extensions.PluginDescriptor
import kotlinx.coroutines.currentCoroutineContext

/**
 * MCP adapter for `mc_status`. Holds no logic: gathering lives in [IdeStatusCollector], toolset states in
 * [CompanionToolsetStates].
 *
 * Implements [PluginAware] so that the platform hands over this plugin's descriptor when it instantiates the
 * extension; that is the public way to learn the plugin's own version.
 *
 * The server resolves the target project before it calls a tool (`McpCallInfo.project` is not nullable) and answers
 * a call without a resolvable project itself, so the tool never runs without one.
 */
class StatusToolset :
    CompanionToolset,
    PluginAware {

    override val toolsetId: String = ID

    private lateinit var collector: IdeStatusCollector

    override fun setPluginDescriptor(pluginDescriptor: PluginDescriptor) {
        collector = IdeStatusCollector(pluginDescriptor)
    }

    @McpTool(name = "mc_status")
    @McpDescription(
        description = "Reports the MCP Companion plugin version, the IDE build number, the project this call " +
            "targets, and every MCP Companion toolset with its state: 'yielded' when official MCP tools (listed in " +
            "officialTools) provide the same capability and the toolset stepped aside, otherwise 'available'. " +
            "'available' does not mean the toolset's tools are switched on: users switch individual tools on the " +
            "MCP Server's tool page, which this tool does not read.",
    )
    suspend fun status(): StatusOutput {
        val project = currentCoroutineContext().project
        return StatusOutput.from(collector.collect(project), CompanionToolsetStates.getInstance().overview())
    }

    companion object {
        const val ID: String = "status"
    }
}
