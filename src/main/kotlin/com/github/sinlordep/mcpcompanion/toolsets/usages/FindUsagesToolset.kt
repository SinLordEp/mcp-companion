package com.github.sinlordep.mcpcompanion.toolsets.usages

import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.scope.SearchScopeSpec
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery
import com.github.sinlordep.mcpcompanion.navigation.usages.FindUsagesRequest
import com.github.sinlordep.mcpcompanion.navigation.usages.psi.findUsagesFor
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.github.sinlordep.mcpcompanion.toolsets.symbols.ToolParameters
import com.github.sinlordep.mcpcompanion.toolsets.toMcpOutput
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext

/** MCP adapter for `mc_find_usages`; the use case is `navigation.usages.FindUsages`. */
class FindUsagesToolset : CompanionToolset {

    override val toolsetId: String = ID

    @McpTool(name = "mc_find_usages")
    @McpDescription(
        description = "Finds every reference to a symbol (class, method, field, variable, ...) in any language the " +
            "IDE understands, like Find Usages; comments and plain text are not searched. Each usage has path (in " +
            "the form the official tools accept), 1-based line and column, origin (project, library_source, " +
            "library_class, sdk, other), the trimmed lineText, enclosing (declarations around it, e.g. Foo.bar) " +
            "and, where the language tells them apart, access (read, write, read_write). When hasMore is true, " +
            "call again with offset=nextOffset.",
    )
    @Suppress("LongParameterList") // One parameter per MCP argument.
    suspend fun findUsages(
        @McpDescription(ToolParameters.PATH) path: String? = null,
        @McpDescription(ToolParameters.LINE) line: Int? = null,
        @McpDescription(ToolParameters.COLUMN) column: Int? = null,
        @McpDescription(ToolParameters.QUALIFIED_NAME) qualifiedName: String? = null,
        @McpDescription(ToolParameters.SCOPE) scope: String? = null,
        @McpDescription("Only reads or only writes of a field or variable. Default any.") access: AccessInput =
            AccessInput.ANY,
        @McpDescription(ToolParameters.LIMIT) limit: Int? = null,
        @McpDescription(ToolParameters.OFFSET) offset: Int? = null,
    ): FindUsagesOutput {
        val project = currentCoroutineContext().project
        return SymbolQuery.of(path, line, column, qualifiedName).flatMap { symbol ->
            SearchScopeSpec.parse(scope).flatMap { searchScope ->
                PageRequest.of(limit, offset).map { page ->
                    FindUsagesRequest(symbol, searchScope, access.filter, page)
                }
            }
        }
            .flatMap { findUsagesFor(project).execute(it) }
            .map(FindUsagesOutput::from)
            .toMcpOutput()
    }

    companion object {
        const val ID: String = "find_usages"
    }
}
