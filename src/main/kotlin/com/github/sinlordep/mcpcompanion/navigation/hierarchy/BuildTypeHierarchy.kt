package com.github.sinlordep.mcpcompanion.navigation.hierarchy

import com.github.sinlordep.mcpcompanion.common.paging.StableResults
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocators

/**
 * Use case of `mc_type_hierarchy`: walks supertypes upward and subtypes downward from one type, breadth-first, up to
 * a depth per direction and a total node limit, and says whether the limits left existing types out.
 *
 * Supertypes keep the language's order (superclass first). Subtypes come from a parallel search in no stable order,
 * so their keys are sorted before the node limit picks among them; otherwise two calls could return different graphs
 * (see [StableResults]). Only the picked types are described by the gateway.
 */
class BuildTypeHierarchy(private val locators: SymbolLocators, private val relations: TypeRelationsGateway) {

    private fun interface Related {
        suspend fun of(type: TypeRef, limit: Int, choose: (List<String>) -> List<String>): ToolResult<List<TypeRef>>
    }

    /** One direction of the walk. [sorted]: whether the gateway's order must be replaced by key order. */
    private class Walk(val up: Boolean, val sorted: Boolean, val related: Related)

    private val upward = Walk(up = true, sorted = false) { type, _, choose -> relations.directSupertypes(type, choose) }
    private val downward = Walk(up = false, sorted = true, relations::directSubtypes)

    suspend fun execute(request: TypeHierarchyRequest): ToolResult<TypeHierarchy> = locators.locate(request.symbol)
        .flatMap { relations.typeOf(it.handle) }
        .flatMap { root ->
            val graph = HierarchyGraph(root, request.maxNodes)
            walkIf(request.direction.up, graph, upward, request.depth)
                .flatMap { walkIf(request.direction.down, graph, downward, request.depth) }
                .map { graph.build() }
        }

    private suspend fun walkIf(wanted: Boolean, graph: HierarchyGraph, walk: Walk, depth: Int): ToolResult<Unit> {
        if (!wanted) return ToolResult.success(Unit)
        var frontier = listOf(HierarchyGraph.ROOT)
        repeat(depth) {
            val next = ArrayList<Int>()
            for (id in frontier) {
                val related = walk.related.of(graph.ref(id), StableResults.SEARCH_LIMIT) { keys ->
                    choose(keys, walk, graph)
                }
                when (related) {
                    is ToolResult.Success -> next += graph.link(id, related.value, walk.up)
                    is ToolResult.Failure -> return related
                }
            }
            frontier = next
        }
        return markIfDepthCutAnything(graph, walk, frontier)
    }

    /**
     * The related types to describe: those already in the graph, plus new ones while the node limit has room, in key
     * order where the walk needs it. Marks the graph truncated when it leaves any out.
     */
    private fun choose(keys: List<String>, walk: Walk, graph: HierarchyGraph): List<String> {
        val distinct = keys.distinct().let { if (walk.sorted) it.sorted() else it }
        var room = graph.room
        val chosen = distinct.filter { graph.contains(it) || room-- > 0 }
        if (chosen.size < distinct.size || keys.size >= StableResults.SEARCH_LIMIT) graph.markTruncated()
        return chosen
    }

    /** Nodes at the depth limit stay unexpanded; the result is truncated if any of them has a relative. */
    private suspend fun markIfDepthCutAnything(
        graph: HierarchyGraph,
        walk: Walk,
        frontier: List<Int>,
    ): ToolResult<Unit> {
        for (id in frontier) {
            var found = false
            val probed = walk.related.of(graph.ref(id), 1) { keys ->
                found = keys.isNotEmpty()
                emptyList()
            }
            if (probed is ToolResult.Failure) return probed
            if (found) {
                graph.markTruncated()
                break
            }
        }
        return ToolResult.success(Unit)
    }
}
