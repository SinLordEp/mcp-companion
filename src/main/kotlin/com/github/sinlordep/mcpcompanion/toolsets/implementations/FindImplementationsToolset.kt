package com.github.sinlordep.mcpcompanion.toolsets.implementations

import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.scope.SearchScopeSpec
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery
import com.github.sinlordep.mcpcompanion.navigation.implementations.FindImplementationsRequest
import com.github.sinlordep.mcpcompanion.navigation.implementations.psi.findImplementationsFor
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.github.sinlordep.mcpcompanion.toolsets.symbols.ToolParameters
import com.github.sinlordep.mcpcompanion.toolsets.toMcpOutput
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext

/** MCP adapter for `mc_find_implementations`. */
class FindImplementationsToolset : CompanionToolset {

    override val toolsetId: String = ID

    @McpTool(name = "mc_find_implementations")
    @McpDescription(
        description = "Like Go to Implementation. direction=down (default): for a class or interface every " +
            "inheritor and implementor at any depth, for a method every overriding or implementing method. " +
            "direction=up: every supertype of a class, or every method a method overrides or implements, nearest " +
            "first (JVM languages). Anonymous classes and lambdas are named <anonymous>. When hasMore is true, call " +
            "again with offset=nextOffset.",
    )
    @Suppress("LongParameterList") // One parameter per MCP argument.
    suspend fun findImplementations(
        @McpDescription(ToolParameters.PATH) path: String? = null,
        @McpDescription(ToolParameters.LINE) line: Int? = null,
        @McpDescription(ToolParameters.COLUMN) column: Int? = null,
        @McpDescription(ToolParameters.QUALIFIED_NAME) qualifiedName: String? = null,
        @McpDescription("down (default) or up.") direction: ImplementationDirectionInput =
            ImplementationDirectionInput.DOWN,
        @McpDescription(ToolParameters.SCOPE + " Only for direction=down.") scope: String? = null,
        @McpDescription(ToolParameters.LIMIT) limit: Int? = null,
        @McpDescription(ToolParameters.OFFSET) offset: Int? = null,
    ): FindImplementationsOutput {
        val project = currentCoroutineContext().project
        return SymbolQuery.of(path, line, column, qualifiedName).flatMap { symbol ->
            SearchScopeSpec.parse(scope).flatMap { searchScope ->
                PageRequest.of(limit, offset).map { page ->
                    FindImplementationsRequest(symbol, direction.direction, searchScope, page)
                }
            }
        }
            .flatMap { findImplementationsFor(project).execute(it) }
            .map(FindImplementationsOutput::from)
            .toMcpOutput()
    }

    companion object {
        const val ID: String = "find_implementations"
    }
}
