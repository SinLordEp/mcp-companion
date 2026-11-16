package com.github.sinlordep.mcpcompanion.toolsets.hierarchy

import com.github.sinlordep.mcpcompanion.navigation.hierarchy.HierarchyDirection
import com.github.sinlordep.mcpcompanion.navigation.hierarchy.TypeHierarchy
import com.github.sinlordep.mcpcompanion.navigation.hierarchy.TypeNode
import com.github.sinlordep.mcpcompanion.toolsets.symbols.OriginOutput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** MCP input values of `direction`. */
@Serializable
enum class HierarchyDirectionInput(val direction: HierarchyDirection) {
    @SerialName("both")
    BOTH(HierarchyDirection.BOTH),

    @SerialName("up")
    UP(HierarchyDirection.UP),

    @SerialName("down")
    DOWN(HierarchyDirection.DOWN),
}

/** MCP output model of `mc_type_hierarchy`: a flat graph; [types] index 0 is the root. */
@Serializable
data class TypeHierarchyOutput(val types: List<TypeNodeOutput>, val truncated: Boolean) {
    companion object {
        fun from(hierarchy: TypeHierarchy): TypeHierarchyOutput =
            TypeHierarchyOutput(hierarchy.nodes.map(TypeNodeOutput::from), hierarchy.truncated)
    }
}

/** One type. [supertypes] and [subtypes] are ids of other entries; null when not explored from this type. */
@Serializable
data class TypeNodeOutput(
    val id: Int,
    val name: String,
    val qualifiedName: String? = null,
    val path: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val origin: OriginOutput? = null,
    val supertypes: List<Int>? = null,
    val subtypes: List<Int>? = null,
) {
    companion object {
        fun from(node: TypeNode): TypeNodeOutput = TypeNodeOutput(
            id = node.id,
            name = node.symbol.name,
            qualifiedName = node.symbol.qualifiedName,
            path = node.symbol.location?.path,
            line = node.symbol.location?.line,
            column = node.symbol.location?.column,
            origin = node.symbol.location?.origin?.let(OriginOutput::of),
            supertypes = node.supertypes,
            subtypes = node.subtypes,
        )
    }
}
