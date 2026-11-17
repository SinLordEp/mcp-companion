package com.github.sinlordep.mcpcompanion.modules

import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.paging.page
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap

/**
 * `graph`: declared dependency edges with scope and exported flag. Without `module` the whole graph; with it a walk
 * from that module in `direction`, at most `depth` edges deep (default: no limit), each edge with its depth.
 * Library dependencies only with `includeLibraries` (and only towards dependencies: libraries depend on nothing).
 */
class GraphModules(private val model: ProjectModelGateway) : ModulesActionHandler {

    override val action = ModulesAction.GRAPH
    override val parameters = setOf(
        ModulesParameter.MODULE,
        ModulesParameter.DIRECTION,
        ModulesParameter.DEPTH,
        ModulesParameter.INCLUDE_LIBRARIES,
        ModulesParameter.LIMIT,
        ModulesParameter.OFFSET,
    )

    override suspend fun handle(request: ModulesArguments): ToolResult<ModulesOutcome> =
        validate(request).flatMap { request.pageRequest() }.flatMap { pageRequest ->
            val graph = DependencyGraph(model.graph(includeLibraries = request.includeLibraries == true))
            val root = request.module
            when {
                root == null -> ToolResult.success(whole(graph, pageRequest))
                root !in graph -> unknownModule(root)
                else -> ToolResult.success(walk(graph, root, request, pageRequest))
            }
        }

    private fun whole(graph: DependencyGraph, pageRequest: PageRequest): ModulesOutcome.Graph {
        val edges = graph.dependencies.map { GraphEdge(it) }
        return ModulesOutcome.Graph(null, null, graph.modules.size, edges.asSequence().page(pageRequest), edges.size)
    }

    private fun walk(
        graph: DependencyGraph,
        root: String,
        request: ModulesArguments,
        pageRequest: PageRequest,
    ): ModulesOutcome.Graph {
        val direction = request.direction ?: GraphDirection.DEPENDENCIES
        val walk = graph.walk(root, direction, request.depth ?: Int.MAX_VALUE)
        val edges = walk.edges.sortedWith(WALK_ORDER)
        return ModulesOutcome.Graph(
            root,
            direction,
            walk.reached.size,
            edges.asSequence().page(pageRequest),
            edges.size,
        )
    }

    private fun validate(request: ModulesArguments): ToolResult<Unit> {
        val depth = request.depth
        val walkOnly = listOfNotNull(
            ModulesParameter.DIRECTION.takeIf { request.direction != null },
            ModulesParameter.DEPTH.takeIf { depth != null },
        )
        return when {
            request.module == null && walkOnly.isNotEmpty() -> ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "${walkOnly.joinToString { it.label }} need module, the root of the walk.",
                "Give module, or omit ${walkOnly.joinToString { it.label }} for the whole graph.",
            )

            depth != null && depth < 1 -> ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "depth must be at least 1, was $depth.",
                "Use depth=1 for direct dependencies, or omit it for all.",
            )

            request.direction == GraphDirection.DEPENDENTS && request.includeLibraries == true -> ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "includeLibraries does not apply to direction=dependents: libraries depend on nothing.",
                "Omit includeLibraries, or use direction=dependencies.",
            )

            else -> ToolResult.success(Unit)
        }
    }

    private companion object {
        /** Depth first, then the graph's edge order: stable pages over a breadth-first walk. */
        val WALK_ORDER: Comparator<GraphEdge> =
            compareBy<GraphEdge> { it.depth }.thenBy(DependencyGraph.EDGE_ORDER) { it.dependency }
    }
}
