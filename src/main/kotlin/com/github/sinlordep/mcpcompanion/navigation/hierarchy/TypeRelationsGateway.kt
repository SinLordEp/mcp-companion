package com.github.sinlordep.mcpcompanion.navigation.hierarchy

import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolHandle

/**
 * A type in the hierarchy. [key] identifies it (qualified name, or file and offset), so a type reached twice is one
 * node.
 */
data class TypeRef(val key: String, val symbol: LocatedSymbol)

/**
 * Gateway boundary of `type hierarchy`: direct relations between types.
 *
 * The relation functions first find the related types as cheap keys and let `choose` decide which of them to
 * describe, and in which order; only the chosen ones become [TypeRef]s. This keeps a search for the subtypes of a
 * widely used type from describing thousands of types the node limit will not keep. `choose` may run again if the
 * underlying read restarts.
 */
interface TypeRelationsGateway {

    /** The type declared by [target]; fails when [target] is not a type the installed languages can relate. */
    suspend fun typeOf(target: SymbolHandle): ToolResult<TypeRef>

    /** The chosen direct supertypes of [type] (superclass and interfaces, in the language's order). */
    suspend fun directSupertypes(type: TypeRef, choose: (List<String>) -> List<String>): ToolResult<List<TypeRef>>

    /** The chosen ones of at most [limit] direct subtypes of [type] in project content; the search stops at [limit]. */
    suspend fun directSubtypes(
        type: TypeRef,
        limit: Int,
        choose: (List<String>) -> List<String>,
    ): ToolResult<List<TypeRef>>
}
