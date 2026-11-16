package com.github.sinlordep.mcpcompanion.navigation.usages

import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.paging.StableResults
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.scope.SearchScopeSpec
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocators
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery

data class FindUsagesRequest(
    val symbol: SymbolQuery,
    val scope: SearchScopeSpec,
    val access: AccessFilter,
    val page: PageRequest,
)

data class FoundUsages(val target: LocatedSymbol, val usages: Page<Usage>)

/**
 * Use case of `mc_find_usages`: every reference to one symbol, optionally only reads or writes, one page at a time.
 * Pages are cut from the complete, sorted list of usage keys, so they are stable across calls (see [StableResults]);
 * only the usages on the page are described.
 */
class FindUsages(private val locators: SymbolLocators, private val usages: UsageSearchGateway) {

    suspend fun execute(request: FindUsagesRequest): ToolResult<FoundUsages> =
        locators.locate(request.symbol).flatMap { target ->
            checkAccessFilter(target, request.access).flatMap {
                usages.findUsages(
                    target = target.handle,
                    scope = request.scope,
                    limit = StableResults.SEARCH_LIMIT,
                    withAccess = request.access != AccessFilter.ANY,
                ) { keys ->
                    StableResults.page(keys, UsageKey.ORDER, request.page) { request.access.accepts(it.access) }
                }
                    .map { FoundUsages(target, it) }
            }
        }

    private suspend fun checkAccessFilter(target: LocatedSymbol, access: AccessFilter): ToolResult<Unit> {
        if (access == AccessFilter.ANY) return ToolResult.success(Unit)
        return usages.distinguishesAccess(target.handle).flatMap { distinguishes ->
            if (distinguishes) {
                ToolResult.success(Unit)
            } else {
                ToolResult.failure(
                    ErrorCode.UNSUPPORTED,
                    "Usages of ${target.name} cannot be told apart by read and write access.",
                    "Omit access (or pass 'any'); read/write filtering works for fields, properties and variables.",
                )
            }
        }
    }
}
