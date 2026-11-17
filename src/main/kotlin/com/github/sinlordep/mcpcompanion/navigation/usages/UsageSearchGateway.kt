package com.github.sinlordep.mcpcompanion.navigation.usages

import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.scope.SearchScopeSpec
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolHandle

/** Gateway boundary of `find usages`: reference search in the IDE. */
interface UsageSearchGateway {

    /** Whether the language of [target] tells read usages from write usages of it. */
    suspend fun distinguishesAccess(target: SymbolHandle): ToolResult<Boolean>

    /**
     * Searches at most [limit] usages of [target] in [scope] as cheap keys, in no particular order (with their access
     * kind only when [withAccess]), lets [pick] choose one page of keys, and describes only the picked usages. Search,
     * pick and description happen in one consistent read of the code; [pick] may run again if that read restarts.
     */
    suspend fun findUsages(
        target: SymbolHandle,
        scope: SearchScopeSpec,
        limit: Int,
        withAccess: Boolean,
        pick: (List<UsageKey>) -> ToolResult<Page<UsageKey>>,
    ): ToolResult<Page<Usage>>
}
