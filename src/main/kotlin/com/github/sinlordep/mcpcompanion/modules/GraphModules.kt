package com.github.sinlordep.mcpcompanion.modules

import com.github.sinlordep.mcpcompanion.common.modulegraph.DependencyGraph
import com.github.sinlordep.mcpcompanion.common.modulegraph.GraphDirection
import com.github.sinlordep.mcpcompanion.common.modulegraph.GraphEdge
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
        ModulesParameter.FORMAT,
        ModulesParameter.LIMIT,
        ModulesParameter.OFFSET,
    )

    override suspend fun handle(request: ModulesArguments): ToolResult<ModulesOutcome> =
        validate(request).flatMap { request.pageRequest() }.flatMap { pageRequest ->
            val graph = DependencyGraph(model.graph(includeLibraries = request.includeLibraries == true))
            val root = request.module
            when {
                root != null && root !in graph -> unknownModule(root)

                else -> {
                    val selection = select(graph, root, request)
                    ToolResult.success(
                        if (request.format == GraphFormat.MERMAID) {
                            val dependencies = selection.edges.map { it.dependency }
                            ModulesOutcome.Diagram(
                                root,
                                selection.direction,
                                selection.reached,
                                MermaidGraph.render(dependencies, selection.nodes, root),
                            )
                        } else {
                            val page = selection.edges.asSequence().page(pageRequest)
                            ModulesOutcome.Graph(
                                root,
                                selection.direction,
                                selection.reached,
                                page,
                                selection.edges.size,
                            )
                        },
                    )
                }
            }
        }

    /** The edges `graph` reports, in a stable order: the whole graph, or the walk from [root]. */
    private class Selection(val direction: GraphDirection?, val nodes: Collection<String>, val edges: List<GraphEdge>) {
        val reached: Int get() = nodes.size
    }

    private fun select(graph: DependencyGraph, root: String?, request: ModulesArguments): Selection {
        if (root == null) return Selection(null, graph.modules, graph.dependencies.map { GraphEdge(it) })
        val direction = request.direction ?: GraphDirection.DEPENDENCIES
        val walk = graph.walk(root, direction, request.depth ?: Int.MAX_VALUE)
        return Selection(direction, walk.reached, walk.edges.sortedWith(WALK_ORDER))
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

            else -> validateFormat(request)
        }
    }

    private fun validateFormat(request: ModulesArguments): ToolResult<Unit> {
        val paged = request.limit != null || request.offset != null
        return if (request.format == GraphFormat.MERMAID && paged) {
            ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "format=mermaid returns the whole graph as one diagram; limit and offset do not apply.",
                "Omit limit and offset, or use format=json to page the edges.",
            )
        } else {
            ToolResult.success(Unit)
        }
    }

    private companion object {
        /** Depth first, then the graph's edge order: stable pages over a breadth-first walk. */
        val WALK_ORDER: Comparator<GraphEdge> =
            compareBy<GraphEdge> { it.depth }.thenBy(DependencyGraph.EDGE_ORDER) { it.dependency }
    }
}
