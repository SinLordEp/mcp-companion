package com.github.sinlordep.mcpcompanion.common

import com.github.sinlordep.mcpcompanion.common.actions.ActionHandler
import com.github.sinlordep.mcpcompanion.common.actions.ActionRegistry
import com.github.sinlordep.mcpcompanion.common.paging.BoundedCollector
import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.paging.StableResults
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.scope.SearchScopeSpec
import com.github.sinlordep.mcpcompanion.common.source.LineExcerpt
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols.code
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols.value
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The small pure building blocks of `common/` added for the navigation tools. */
class CommonInputsTest {

    // ---- StableResults and BoundedCollector ----

    @Test
    fun `stable results are filtered, sorted, then paged`() {
        val page = StableResults.page(listOf(5, 3, 9, 1, 4), naturalOrder(), PageRequest.of(2, 1).value()) { it != 4 }
        assertEquals(listOf(3, 5), page.value().items)
        assertTrue(page.value().hasMore)
        val tooMany = StableResults.page(List(StableResults.SEARCH_LIMIT) { it }, naturalOrder(), PageRequest.FIRST)
        assertEquals(ErrorCode.INVALID_ARGUMENT, tooMany.code())
    }

    @Test
    fun `bounded collector stops the search at its limit`() {
        val collector = BoundedCollector<Int>(3)
        assertEquals(listOf(true, true, false, false), (1..4).map(collector::offer))
        assertEquals(listOf(1, 2, 3), collector.items())
    }

    // ---- SymbolQuery ----

    @Test
    fun `a symbol is named by a full position or by a qualified name, never both or neither`() {
        assertEquals(SymbolQuery.AtPosition("a/B.java", 3, 4), SymbolQuery.of(" a/B.java ", 3, 4, null).value())
        assertEquals(SymbolQuery.ByQualifiedName("a.B#c"), SymbolQuery.of(null, null, null, " a.B#c ").value())
        assertEquals(SymbolQuery.ByQualifiedName("a.B"), SymbolQuery.of("  ", null, null, "a.B").value())
        for (partial in listOf(
            SymbolQuery.of(null, null, null, null),
            SymbolQuery.of("", null, null, "  "),
            SymbolQuery.of("a/B.java", 3, null, null),
            SymbolQuery.of(null, 3, 4, null),
            SymbolQuery.of("a/B.java", 3, 4, "a.B"),
            SymbolQuery.of(null, 3, null, "a.B"),
        )) {
            assertEquals(partial.toString(), ErrorCode.INVALID_ARGUMENT, partial.code())
        }
    }

    // ---- SearchScopeSpec ----

    @Test
    fun `scopes parse from the client syntax`() {
        assertEquals(SearchScopeSpec.Project, SearchScopeSpec.parse(null).value())
        assertEquals(SearchScopeSpec.Project, SearchScopeSpec.parse(" project ").value())
        assertEquals(SearchScopeSpec.ProjectAndLibraries, SearchScopeSpec.parse("project_and_libraries").value())
        assertEquals(SearchScopeSpec.Module("core"), SearchScopeSpec.parse("module: core").value())
        assertEquals(SearchScopeSpec.Directory("src/a b"), SearchScopeSpec.parse("dir:src/a b").value())
        for (bad in listOf("all", "module:", "dir: ", "everything", "Project")) {
            assertEquals(bad, ErrorCode.INVALID_ARGUMENT, SearchScopeSpec.parse(bad).code())
        }
    }

    // ---- LineExcerpt ----

    @Test
    fun `line excerpts are trimmed and long lines are cut around the column`() {
        assertEquals("int x = 1;", LineExcerpt.of("    int x = 1;   ", 5))
        assertEquals("", LineExcerpt.of("   \t", 1))
        val long = "a".repeat(100) + "TARGET" + "b".repeat(100)
        val excerpt = LineExcerpt.of(long, 101, maxLength = 40)
        assertTrue(excerpt, "TARGET" in excerpt)
        assertTrue(excerpt.startsWith("…") && excerpt.endsWith("…"))
        assertEquals(40, excerpt.length)
        val atStart = LineExcerpt.of(long, 1, maxLength = 40)
        assertTrue(atStart.startsWith("aaa") && atStart.endsWith("…"))
        assertFalse(LineExcerpt.of(long, long.length, maxLength = 40).endsWith("…"))
    }

    // ---- ActionRegistry ----

    @Test
    fun `actions dispatch to their handler`() = runBlocking {
        val registry = ActionRegistry(
            mapOf(
                "one" to ActionHandler<Int, String> { ToolResult.success("one:$it") },
                "two" to ActionHandler<Int, String> { ToolResult.success("two:$it") },
            ),
        )
        assertEquals("two:7", registry.dispatch("two", 7).value())
        val unknown = registry.dispatch("three", 7)
        assertEquals(ErrorCode.INVALID_ARGUMENT, unknown.code())
        assertTrue((unknown as ToolResult.Failure).error.nextStep!!.contains("one, two"))
    }
}
