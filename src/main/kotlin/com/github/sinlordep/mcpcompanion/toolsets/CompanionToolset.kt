package com.github.sinlordep.mcpcompanion.toolsets

import com.github.sinlordep.mcpcompanion.McpCompanionBundle
import com.github.sinlordep.mcpcompanion.yield.ToolsetState
import com.intellij.mcpserver.McpToolset

/**
 * Contract of every MCP Companion toolset, and the marker that tells our toolsets apart from everyone else's.
 *
 * A toolset only supplies [toolsetId] and its `@McpTool` functions. Its name and description come from
 * `McpCompanionBundle` under `toolset.<id>.name` and `toolset.<id>.description`; whether it is enabled comes from the
 * yield decision. Users switch tools on and off on the bundled MCP Server's tool page, not here.
 */
interface CompanionToolset : McpToolset {

    /** Stable id: key in the equivalence catalog and the bundle. Never change it once released. */
    val toolsetId: String

    /** False when an official equivalent exists. The server asks once and again only when plugins change. */
    override fun isEnabled(): Boolean =
        CompanionToolsetStates.getInstance().stateOf(toolsetId) == ToolsetState.Available

    override fun isExperimental(): Boolean = false

    override fun displayName(): String = McpCompanionBundle.message("toolset.$toolsetId.name")

    override fun displayDescription(): String = McpCompanionBundle.message("toolset.$toolsetId.description")
}
