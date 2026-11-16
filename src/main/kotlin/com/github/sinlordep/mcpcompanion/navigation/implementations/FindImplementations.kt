package com.github.sinlordep.mcpcompanion.navigation.implementations

import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.paging.StableResults
import com.github.sinlordep.mcpcompanion.common.paging.page
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.scope.SearchScopeSpec
import com.github.sinlordep.mcpcompanion.common.source.SourceKey
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocators
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery

/** [DOWN]: implementations, inheritors and overriders. [UP]: the supertypes or super methods the symbol has. */
enum class ImplementationDirection { DOWN, UP }

/** [scope] applies to [ImplementationDirection.DOWN] only: super declarations are found wherever they are. */
data class FindImplementationsRequest(
    val symbol: SymbolQuery,
    val direction: ImplementationDirection,
    val scope: SearchScopeSpec,
    val page: PageRequest,
)

data class FoundImplementations(val target: LocatedSymbol, val results: Page<LocatedSymbol>)

/**
 * Use case of `mc_find_implementations`. Implementations are paged from the complete, sorted list of their keys, so
 * pages are stable across calls (see [StableResults]) and only the page is described; super declarations come
 * nearest first.
 */
class FindImplementations(private val locators: SymbolLocators, private val search: ImplementationSearchGateway) {

    suspend fun execute(request: FindImplementationsRequest): ToolResult<FoundImplementations> =
        locators.locate(request.symbol).flatMap { target ->
            when (request.direction) {
                ImplementationDirection.DOWN ->
                    search.implementations(target.handle, request.scope, StableResults.SEARCH_LIMIT) { keys ->
                        StableResults.page(keys, SourceKey.ORDER, request.page)
                    }

                ImplementationDirection.UP ->
                    search.superDeclarations(target.handle).map { it.asSequence().page(request.page) }
            }.map { FoundImplementations(target, it) }
        }
}
