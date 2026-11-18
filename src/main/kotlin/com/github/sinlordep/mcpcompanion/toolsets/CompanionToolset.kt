package com.github.sinlordep.mcpcompanion.toolsets

import com.github.sinlordep.mcpcompanion.McpCompanionBundle
import com.github.sinlordep.mcpcompanion.yield.ToolsetState
import com.intellij.mcpserver.McpToolset

/**
 * Contract of every MCP Companion toolset, and the marker that tells our toolsets apart from everyone else's.
 *
 * A toolset only supplies [toolsetId] and its `@McpTool` functions. Its texts come from `McpCompanionBundle` under
 * `toolset.<id>.name` and `toolset.<id>.description`; whether it is enabled comes from the yield decision. Users switch
 * tools on and off on the bundled MCP Server's tool page (Exposed Tools), not here.
 *
 * Each toolset holds one tool, so the texts are split by where they appear: Exposed Tools, a list shared with
 * JetBrains' toolsets, shows [displayName] ("MCP Companion: <short name>") above the tool's own name and description,
 * and no group description, which would only repeat the tool's. Our own settings page, where every row is ours, shows
 * [shortName] and [summary].
 */
interface CompanionToolset : McpToolset {

    /** Stable id: key in the equivalence catalog and the bundle. Never change it once released. */
    val toolsetId: String

    /** Name without our prefix, e.g. "Find Usages". */
    val shortName: String
        get() = McpCompanionBundle.message("toolset.$toolsetId.name")

    /** What the toolset is for, in one line. */
    val summary: String
        get() = McpCompanionBundle.message("toolset.$toolsetId.description")

    /** False when an official equivalent exists. The server asks once and again only when plugins change. */
    override fun isEnabled(): Boolean =
        CompanionToolsetStates.getInstance().stateOf(toolsetId) == ToolsetState.Available

    override fun isExperimental(): Boolean = false

    /** Group title on Exposed Tools; the prefix tells our toolsets apart from JetBrains' ones there. */
    override fun displayName(): String = McpCompanionBundle.message("toolset.title", shortName)

    /** None: Exposed Tools then shows no group description line, since the single tool below describes itself. */
    override fun displayDescription(): String? = null
}
