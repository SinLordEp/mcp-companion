package com.github.sinlordep.mcpcompanion.navigation.implementations

import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.paging.mapNotNull
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.scope.SearchScopeSpec
import com.github.sinlordep.mcpcompanion.common.source.SourceKey
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolHandle
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols.code
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols.value
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FindImplementationsTest {

    private class FakeGateway(private val supersSupported: Boolean = true) : ImplementationSearchGateway {
        val calls = mutableListOf<String>()

        var described = 0

        override suspend fun implementations(
            target: SymbolHandle,
            scope: SearchScopeSpec,
            limit: Int,
            pick: (List<SourceKey>) -> ToolResult<Page<SourceKey>>,
        ): ToolResult<Page<LocatedSymbol>> {
            calls += "down $scope"
            // Unordered, as from a parallel search.
            val byKey = listOf("C", "A", "B").associateBy { SourceKey("x/$it.java", 0) }
            return pick(byKey.keys.toList()).map { page ->
                page.mapNotNull { key -> byKey[key]?.let(FakeSymbols::symbol)?.also { described++ } }
            }
        }

        override suspend fun superDeclarations(target: SymbolHandle): ToolResult<List<LocatedSymbol>> {
            calls += "up"
            return if (supersSupported) {
                ToolResult.success(listOf("Near", "Far").map(FakeSymbols::symbol))
            } else {
                ToolResult.failure(ErrorCode.UNSUPPORTED, "no")
            }
        }
    }

    private fun request(direction: ImplementationDirection, limit: Int = 100) = FindImplementationsRequest(
        FakeSymbols.ANY_POSITION,
        direction,
        SearchScopeSpec.Module("core"),
        PageRequest.of(limit, 0).value(),
    )

    private fun run(gateway: FakeGateway, request: FindImplementationsRequest) =
        runBlocking { FindImplementations(FakeSymbols.locating(FakeSymbols.symbol("Foo")), gateway).execute(request) }

    @Test
    fun `down searches implementations in the scope and pages them in reading order`() {
        val gateway = FakeGateway()
        val found = run(gateway, request(ImplementationDirection.DOWN, limit = 2)).value()
        assertEquals("Foo", found.target.name)
        assertEquals(listOf("A", "B"), found.results.items.map { it.name })
        assertTrue(found.results.hasMore)
        assertEquals(listOf("down Module(name=core)"), gateway.calls)
        assertEquals("only the page is described", 2, gateway.described)
    }

    @Test
    fun `up asks for super declarations and passes unsupported on`() {
        val gateway = FakeGateway()
        assertEquals(
            listOf("Near", "Far"),
            run(gateway, request(ImplementationDirection.UP)).value().results.items.map {
                it.name
            },
        )
        assertEquals(listOf("up"), gateway.calls)
        assertEquals(
            ErrorCode.UNSUPPORTED,
            run(FakeGateway(supersSupported = false), request(ImplementationDirection.UP)).code(),
        )
    }

    @Test
    fun `a symbol that cannot be located is reported without searching`() {
        val gateway = FakeGateway()
        val result = runBlocking {
            FindImplementations(
                FakeSymbols.failing(ErrorCode.NOT_FOUND),
                gateway,
            ).execute(request(ImplementationDirection.DOWN))
        }
        assertEquals(ErrorCode.NOT_FOUND, result.code())
        assertTrue(gateway.calls.isEmpty())
    }
}
