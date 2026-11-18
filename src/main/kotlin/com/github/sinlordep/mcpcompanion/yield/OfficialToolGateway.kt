package com.github.sinlordep.mcpcompanion.yield

/**
 * Names of the official MCP tools: those that JetBrains ships (see [ToolsetOwner.isOfficial]). Tools of third-party
 * plugins are not official, so they never make a toolset of ours step aside. Gateway boundary of `yield/`.
 */
fun interface OfficialToolGateway {
    fun officialToolNames(): Set<String>
}
