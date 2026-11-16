package com.github.sinlordep.mcpcompanion.yield

/** Whether one of our toolsets serves its tools or steps aside for official ones. */
sealed interface ToolsetState {

    /**
     * No official equivalent is present, so the toolset offers its tools. Says nothing about whether the user has
     * switched them off on the MCP Server's tool page, which we deliberately do not read.
     */
    data object Available : ToolsetState

    /** Steps aside because the official [officialTools] provide the same capability. */
    data class Yielded(val officialTools: Set<String>) : ToolsetState
}
