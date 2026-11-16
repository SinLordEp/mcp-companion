package com.github.sinlordep.mcpcompanion.toolsets.hierarchy

import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery
import com.github.sinlordep.mcpcompanion.navigation.hierarchy.TypeHierarchyRequest
import com.github.sinlordep.mcpcompanion.navigation.hierarchy.psi.buildTypeHierarchyFor
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.github.sinlordep.mcpcompanion.toolsets.symbols.ToolParameters
import com.github.sinlordep.mcpcompanion.toolsets.toMcpOutput
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext

/** MCP adapter for `mc_type_hierarchy`. */
class TypeHierarchyToolset : CompanionToolset {

    override val toolsetId: String = ID

    @McpTool(name = "mc_type_hierarchy")
    @McpDescription(
        description = "Type hierarchy of a class or interface (JVM languages): supertypes upward (library ones " +
            "too; see origin) and subtypes downward (searched in project content), breadth-first. Returns a graph: " +
            "types[0] is the root, supertypes/subtypes list ids of other entries (absent = not explored). " +
            "truncated=true means depth or maxNodes left existing types out; raise them or start from another type.",
    )
    @Suppress("LongParameterList") // One parameter per MCP argument.
    suspend fun typeHierarchy(
        @McpDescription(ToolParameters.PATH) path: String? = null,
        @McpDescription(ToolParameters.LINE) line: Int? = null,
        @McpDescription(ToolParameters.COLUMN) column: Int? = null,
        @McpDescription(ToolParameters.QUALIFIED_NAME) qualifiedName: String? = null,
        @McpDescription(
            "both (default), up or down.",
        ) direction: HierarchyDirectionInput = HierarchyDirectionInput.BOTH,
        @McpDescription("Levels per direction, 1-20. Default 5.") depth: Int? = null,
        @McpDescription("Most types in the result, 1-1000. Default 100.") maxNodes: Int? = null,
    ): TypeHierarchyOutput {
        val project = currentCoroutineContext().project
        return SymbolQuery.of(path, line, column, qualifiedName)
            .flatMap { TypeHierarchyRequest.of(it, direction.direction, depth, maxNodes) }
            .flatMap { buildTypeHierarchyFor(project).execute(it) }
            .map(TypeHierarchyOutput::from)
            .toMcpOutput()
    }

    companion object {
        const val ID: String = "type_hierarchy"
    }
}
