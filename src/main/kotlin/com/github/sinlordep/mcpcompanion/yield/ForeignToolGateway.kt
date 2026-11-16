package com.github.sinlordep.mcpcompanion.yield

/** Names of the MCP tools that the platform and other plugins contribute. Gateway boundary of `yield/`. */
fun interface ForeignToolGateway {
    fun foreignToolNames(): Set<String>
}
