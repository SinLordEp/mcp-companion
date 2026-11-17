package com.github.sinlordep.mcpcompanion.common.symbols

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SymbolLocatorsTest {

    private object Handle : SymbolHandle

    private fun symbol(name: String) = LocatedSymbol(name, null, null, Handle)

    private val notFound = ToolResult.failure(ErrorCode.NOT_FOUND, "nope")

    private val byPosition = SymbolLocator<SymbolQuery.AtPosition> { ToolResult.success(symbol("at-${it.line}")) }

    @Test
    fun `position queries go to the position strategy`() = runBlocking {
        val result = SymbolLocators(byPosition, emptyList()).locate(SymbolQuery.AtPosition("a.txt", 3, 1))
        assertEquals("at-3", (result as ToolResult.Success).value.name)
    }

    @Test
    fun `qualified names without any language locator are unsupported`() = runBlocking {
        val result = SymbolLocators(byPosition, emptyList()).locate(SymbolQuery.ByQualifiedName("a.B"))
        assertEquals(ErrorCode.UNSUPPORTED, (result as ToolResult.Failure).error.code)
    }

    @Test
    fun `first language locator that finds the symbol wins`() = runBlocking {
        val found = ToolResult.success(symbol("B"))
        val locators = SymbolLocators(
            byPosition,
            listOf(
                SymbolLocator { notFound },
                SymbolLocator { found },
                SymbolLocator { error("must not be asked after a hit") },
            ),
        )
        assertSame(found, locators.locate(SymbolQuery.ByQualifiedName("a.B")))
    }

    @Test
    fun `when no language locator finds it the last failure is reported`() = runBlocking {
        val ambiguous = ToolResult.failure(ErrorCode.AMBIGUOUS, "two")
        val locators = SymbolLocators(byPosition, listOf(SymbolLocator { notFound }, SymbolLocator { ambiguous }))
        assertSame(ambiguous, locators.locate(SymbolQuery.ByQualifiedName("a.B")))
    }
}
