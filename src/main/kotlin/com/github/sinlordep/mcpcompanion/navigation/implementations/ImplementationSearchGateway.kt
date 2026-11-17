package com.github.sinlordep.mcpcompanion.navigation.implementations

import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.scope.SearchScopeSpec
import com.github.sinlordep.mcpcompanion.common.source.SourceKey
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolHandle

/** Gateway boundary of `find implementations`. */
interface ImplementationSearchGateway {

    /**
     * Searches at most [limit] declarations in [scope] that implement, inherit from or override [target], at any
     * depth, as cheap keys in no particular order; lets [pick] choose one page of keys and describes only those, all in
     * one consistent read of the code. [pick] may run again if that read restarts.
     */
    suspend fun implementations(
        target: SymbolHandle,
        scope: SearchScopeSpec,
        limit: Int,
        pick: (List<SourceKey>) -> ToolResult<Page<SourceKey>>,
    ): ToolResult<Page<LocatedSymbol>>

    /**
     * Every supertype of a class, or every method a method overrides or implements, transitively and nearest first.
     * `UNSUPPORTED` when no installed language support knows the symbol's super declarations.
     */
    suspend fun superDeclarations(target: SymbolHandle): ToolResult<List<LocatedSymbol>>
}
