package com.github.sinlordep.mcpcompanion.navigation.hierarchy

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery

enum class HierarchyDirection(val up: Boolean, val down: Boolean) {
    UP(up = true, down = false),
    DOWN(up = false, down = true),
    BOTH(up = true, down = true),
}

/** Create through [of], which validates client input. */
@ConsistentCopyVisibility
data class TypeHierarchyRequest private constructor(
    val symbol: SymbolQuery,
    val direction: HierarchyDirection,
    val depth: Int,
    val maxNodes: Int,
) {
    companion object {
        const val DEFAULT_DEPTH: Int = 5
        const val MAX_DEPTH: Int = 20
        const val DEFAULT_MAX_NODES: Int = 100
        const val MAX_NODES: Int = 1_000

        fun of(
            symbol: SymbolQuery,
            direction: HierarchyDirection,
            depth: Int?,
            maxNodes: Int?,
        ): ToolResult<TypeHierarchyRequest> {
            val effectiveDepth = depth ?: DEFAULT_DEPTH
            val effectiveMaxNodes = maxNodes ?: DEFAULT_MAX_NODES
            return when {
                effectiveDepth !in 1..MAX_DEPTH -> ToolResult.failure(
                    ErrorCode.INVALID_ARGUMENT,
                    "depth must be between 1 and $MAX_DEPTH, was $effectiveDepth",
                    "Omit depth to get $DEFAULT_DEPTH levels in each direction.",
                )

                effectiveMaxNodes !in 1..MAX_NODES -> ToolResult.failure(
                    ErrorCode.INVALID_ARGUMENT,
                    "maxNodes must be between 1 and $MAX_NODES, was $effectiveMaxNodes",
                    "Omit maxNodes to get at most $DEFAULT_MAX_NODES types.",
                )

                else -> ToolResult.success(TypeHierarchyRequest(symbol, direction, effectiveDepth, effectiveMaxNodes))
            }
        }
    }
}

/**
 * One type of the hierarchy. [supertypes] and [subtypes] hold node ids; null means that direction was not explored
 * from this node (not asked for, or beyond the depth or node limit), an empty list that there is nothing there.
 */
data class TypeNode(val id: Int, val symbol: LocatedSymbol, val supertypes: List<Int>?, val subtypes: List<Int>?)

/**
 * The explored part of a type hierarchy: a graph, because a type can be reached on several paths (an interface
 * implemented twice). Node 0 is the root. [truncated] is true when the depth or node limit left out existing types.
 */
data class TypeHierarchy(val nodes: List<TypeNode>, val truncated: Boolean)
