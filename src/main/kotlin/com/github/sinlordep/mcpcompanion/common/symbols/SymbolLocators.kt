package com.github.sinlordep.mcpcompanion.common.symbols

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/**
 * Picks the [SymbolLocator] strategy for a query.
 *
 * Qualified names are language-specific, so there may be several locators (one per installed language plugin) or
 * none; they are tried in order and the first hit wins.
 */
class SymbolLocators(
    private val byPosition: SymbolLocator<SymbolQuery.AtPosition>,
    private val byQualifiedName: List<SymbolLocator<SymbolQuery.ByQualifiedName>>,
) {

    suspend fun locate(query: SymbolQuery): ToolResult<LocatedSymbol> = when (query) {
        is SymbolQuery.AtPosition -> byPosition.locate(query)
        is SymbolQuery.ByQualifiedName -> locateByQualifiedName(query)
    }

    private suspend fun locateByQualifiedName(query: SymbolQuery.ByQualifiedName): ToolResult<LocatedSymbol> {
        var lastFailure: ToolResult<LocatedSymbol> = ToolResult.failure(
            ErrorCode.UNSUPPORTED,
            "No installed language plugin can look up symbols by qualified name.",
            "Locate the symbol by file path, line and column instead.",
        )
        for (locator in byQualifiedName) {
            val result = locator.locate(query)
            if (result is ToolResult.Success) return result
            lastFailure = result
        }
        return lastFailure
    }
}
