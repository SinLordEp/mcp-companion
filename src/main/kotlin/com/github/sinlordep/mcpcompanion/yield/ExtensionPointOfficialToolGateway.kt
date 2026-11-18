package com.github.sinlordep.mcpcompanion.yield

import com.intellij.mcpserver.McpToolset

/**
 * Lists official MCP tool names by reflecting over the toolsets on the bundled server's `mcpToolset` extension
 * point, the same source the server builds its own list from. Toolsets of this plugin are excluded, and so are those
 * of third-party plugins: only a toolset whose [ToolsetOwner] is official counts. Together with its
 * [McpToolNameReader] and [ToolsetOwnerGateway], the only code in `yield/` that touches the platform.
 *
 * Safe to call from `McpToolset.isEnabled()`: it never calls `isEnabled()` (so it sees disabled toolsets too and
 * cannot recurse), never asks the server for its tool list, and never throws because a foreign class cannot be
 * reflected (see [McpToolNameReader]). Each call reads the current extension list, so installed or removed plugins
 * are seen at once; the owner and the reflection per class are memoised.
 *
 * @param isOwn tells this plugin's own toolsets apart; they are excluded.
 */
class ExtensionPointOfficialToolGateway internal constructor(
    private val isOwn: (McpToolset) -> Boolean,
    private val owners: ToolsetOwnerGateway,
    private val reader: McpToolNameReader,
    private val toolsets: () -> List<McpToolset>,
) : OfficialToolGateway {

    constructor(isOwn: (McpToolset) -> Boolean) :
        this(isOwn, PluginClassLoaderOwnerGateway(), McpToolNameReader(), { McpToolset.EP.extensionList })

    override fun officialToolNames(): Set<String> = toolsets()
        .filter { !isOwn(it) && owners.ownerOf(it.javaClass).isOfficial }
        .flatMapTo(linkedSetOf()) { reader.toolNamesOf(it.javaClass) }
}
