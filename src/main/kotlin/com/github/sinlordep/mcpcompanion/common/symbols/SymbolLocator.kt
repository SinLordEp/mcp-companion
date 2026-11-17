package com.github.sinlordep.mcpcompanion.common.symbols

import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/** Strategy that finds a symbol for one kind of [SymbolQuery]. Gateways implement it. */
fun interface SymbolLocator<in Q : SymbolQuery> {
    suspend fun locate(query: Q): ToolResult<LocatedSymbol>
}
