package com.github.sinlordep.mcpcompanion.toolsets.implementations

import com.github.sinlordep.mcpcompanion.navigation.implementations.FoundImplementations
import com.github.sinlordep.mcpcompanion.navigation.implementations.ImplementationDirection
import com.github.sinlordep.mcpcompanion.toolsets.symbols.SymbolOutput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** MCP input values of `direction`. */
@Serializable
enum class ImplementationDirectionInput(val direction: ImplementationDirection) {
    @SerialName("down")
    DOWN(ImplementationDirection.DOWN),

    @SerialName("up")
    UP(ImplementationDirection.UP),
}

/** MCP output model of `mc_find_implementations`. */
@Serializable
data class FindImplementationsOutput(
    val symbol: SymbolOutput,
    val results: List<SymbolOutput>,
    val hasMore: Boolean,
    val nextOffset: Int? = null,
) {
    companion object {
        fun from(found: FoundImplementations): FindImplementationsOutput = FindImplementationsOutput(
            symbol = SymbolOutput.from(found.target),
            results = found.results.items.map(SymbolOutput::from),
            hasMore = found.results.hasMore,
            nextOffset = found.results.nextOffset,
        )
    }
}
