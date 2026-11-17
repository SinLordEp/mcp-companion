package com.github.sinlordep.mcpcompanion.navigation.usages

import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.paging.StableResults
import com.github.sinlordep.mcpcompanion.common.paging.mapNotNull
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.scope.SearchScopeSpec
import com.github.sinlordep.mcpcompanion.common.source.SourceKey
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolHandle
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols.code
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols.value
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FindUsagesTest {

    /**
     * Follows the gateway contract over a fixed list: keys for all usages (access only on request), [pick] chooses,
     * only the picked usages are described. Counts descriptions and records what it was asked.
     */
    private class FakeGateway(
        private val usages: List<Usage>,
        private val distinguishes: Boolean = true,
        private val failure: ToolResult<Nothing>? = null,
    ) : UsageSearchGateway {
        var accessAsked = 0
        var limit = 0
        var described = 0
        var keysHadAccess = false
        var scope: SearchScopeSpec? = null

        override suspend fun distinguishesAccess(target: SymbolHandle): ToolResult<Boolean> {
            accessAsked++
            return ToolResult.success(distinguishes)
        }

        override suspend fun findUsages(
            target: SymbolHandle,
            scope: SearchScopeSpec,
            limit: Int,
            withAccess: Boolean,
            pick: (List<UsageKey>) -> ToolResult<Page<UsageKey>>,
        ): ToolResult<Page<Usage>> {
            failure?.let { return it }
            this.scope = scope
            this.limit = limit
            val byKey = usages.take(limit).associateBy { usage ->
                UsageKey(SourceKey("x/Use.java", usage.site.location.line), usage.access.takeIf { withAccess })
            }
            keysHadAccess = byKey.keys.any { it.access != null }
            return pick(byKey.keys.toList()).map { page ->
                page.mapNotNull { key -> byKey[key]?.also { described++ } }
            }
        }
    }

    private val target = FakeSymbols.symbol("Foo")

    /** In search order, which is not reading order: the platform searches files in parallel. */
    private val usages = listOf(
        Usage(FakeSymbols.site(4), AccessKind.READ),
        Usage(FakeSymbols.site(2), AccessKind.WRITE),
        Usage(FakeSymbols.site(5), null),
        Usage(FakeSymbols.site(1), AccessKind.READ),
        Usage(FakeSymbols.site(3), AccessKind.READ_WRITE),
    )

    private fun request(access: AccessFilter = AccessFilter.ANY, limit: Int = 100, offset: Int = 0) = FindUsagesRequest(
        FakeSymbols.ANY_POSITION,
        SearchScopeSpec.Project,
        access,
        PageRequest.of(limit, offset).value(),
    )

    private fun run(gateway: FakeGateway, request: FindUsagesRequest) =
        runBlocking { FindUsages(FakeSymbols.locating(target), gateway).execute(request) }

    private fun lines(result: ToolResult<FoundUsages>) = result.value().usages.items.map { it.site.location.line }

    @Test
    fun `all usages of the located symbol, without asking about access`() {
        val gateway = FakeGateway(usages)
        val result = run(gateway, request())
        assertEquals(target, result.value().target)
        assertEquals(listOf(1, 2, 3, 4, 5), lines(result))
        assertEquals(0, gateway.accessAsked)
        assertEquals(SearchScopeSpec.Project, gateway.scope)
    }

    @Test
    fun `read and write filters both keep read-write usages`() {
        assertEquals(listOf(1, 3, 4), lines(run(FakeGateway(usages), request(AccessFilter.READ))))
        assertEquals(listOf(2, 3), lines(run(FakeGateway(usages), request(AccessFilter.WRITE))))
    }

    @Test
    fun `filtering happens before paging, and pages follow reading order whatever the search order`() {
        val page = run(FakeGateway(usages), request(AccessFilter.READ, limit = 1)).value().usages
        assertEquals(1, page.items.single().site.location.line)
        assertTrue(page.hasMore)
        assertEquals(1, page.nextOffset)

        val next = run(FakeGateway(usages.reversed()), request(AccessFilter.READ, limit = 2, offset = 1)).value().usages
        assertEquals(listOf(3, 4), next.items.map { it.site.location.line })
        assertFalse(next.hasMore)
    }

    @Test
    fun `only the usages of the page are described, and access is looked up only for a filter`() {
        val many = List(500) { Usage(FakeSymbols.site(500 - it), null) }
        val gateway = FakeGateway(many)
        val page = run(gateway, request(limit = 3, offset = 10)).value().usages
        assertEquals(listOf(11, 12, 13), page.items.map { it.site.location.line })
        assertEquals("described rows = page size, not result size", 3, gateway.described)
        assertFalse(gateway.keysHadAccess)

        val filtered = FakeGateway(usages)
        run(filtered, request(AccessFilter.WRITE))
        assertTrue(filtered.keysHadAccess)
        assertEquals(2, filtered.described)
    }

    @Test
    fun `the search is bounded and too many results are refused rather than paged unstably`() {
        val gateway = FakeGateway(usages)
        run(gateway, request())
        assertEquals(StableResults.SEARCH_LIMIT, gateway.limit)
        val many = List(StableResults.SEARCH_LIMIT) { Usage(FakeSymbols.site(it + 1), null) }
        assertEquals(ErrorCode.INVALID_ARGUMENT, run(FakeGateway(many), request(limit = 1)).code())
        val enough = List(StableResults.MAX_RESULTS) { Usage(FakeSymbols.site(it + 1), null) }
        assertTrue(run(FakeGateway(enough), request(limit = 1)).value().usages.hasMore)
    }

    @Test
    fun `access filter on a symbol without access kinds is unsupported`() {
        val result = run(FakeGateway(usages, distinguishes = false), request(AccessFilter.WRITE))
        assertEquals(ErrorCode.UNSUPPORTED, result.code())
    }

    @Test
    fun `locator and gateway failures are passed on`() {
        val notFound = runBlocking {
            FindUsages(FakeSymbols.failing(ErrorCode.AMBIGUOUS), FakeGateway(usages)).execute(request())
        }
        assertEquals(ErrorCode.AMBIGUOUS, notFound.code())
        val gone = run(FakeGateway(usages, failure = ToolResult.failure(ErrorCode.NOT_FOUND, "gone")), request())
        assertEquals(ErrorCode.NOT_FOUND, gone.code())
    }
}
