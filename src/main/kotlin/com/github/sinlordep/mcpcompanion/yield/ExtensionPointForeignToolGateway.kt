package com.github.sinlordep.mcpcompanion.yield

import com.intellij.mcpserver.McpToolset

/**
 * Lists foreign MCP tool names by reflecting over every toolset on the bundled server's `mcpToolset` extension point,
 * the same source the server builds its own list from. Together with its [McpToolNameReader], the only code in
 * `yield/` that touches the platform.
 *
 * Safe to call from `McpToolset.isEnabled()`: it never calls `isEnabled()` (so it sees disabled toolsets too and
 * cannot recurse), never asks the server for its tool list, and never throws because a foreign class cannot be
 * reflected (see [McpToolNameReader]). Each call reads the current extension list, so installed or removed plugins
 * are seen at once; the reflection per class is memoised.
 *
 * @param isOwn tells this plugin's own toolsets apart; they are excluded.
 */
class ExtensionPointForeignToolGateway internal constructor(
    private val isOwn: (McpToolset) -> Boolean,
    private val reader: McpToolNameReader,
) : ForeignToolGateway {

    constructor(isOwn: (McpToolset) -> Boolean) : this(isOwn, McpToolNameReader())

    override fun foreignToolNames(): Set<String> = McpToolset.EP.extensionList
        .filterNot(isOwn)
        .flatMapTo(linkedSetOf()) { reader.toolNamesOf(it.javaClass) }
}
