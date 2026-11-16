package com.github.sinlordep.mcpcompanion.testing

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.source.CodeSite
import com.github.sinlordep.mcpcompanion.common.symbols.FileOrigin
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolHandle
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocation
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocator
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocators
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery

/** Platform-free stand-ins for unit tests of use cases. */
object FakeSymbols {

    /** A handle that only knows the name of its symbol. */
    data class NamedHandle(val name: String) : SymbolHandle

    fun symbol(name: String): LocatedSymbol =
        LocatedSymbol(name, "x.$name", SymbolLocation("x/$name.java", 1, 1, FileOrigin.PROJECT), NamedHandle(name))

    fun site(line: Int): CodeSite =
        CodeSite(SymbolLocation("x/Use.java", line, 1, FileOrigin.PROJECT), "line $line", null)

    val ANY_POSITION: SymbolQuery = SymbolQuery.AtPosition("x/Use.java", 1, 1)

    /** Locators that answer every query with [symbol]. */
    fun locating(symbol: LocatedSymbol): SymbolLocators = SymbolLocators(
        SymbolLocator {
            ToolResult.success(symbol)
        },
        listOf(SymbolLocator { ToolResult.success(symbol) }),
    )

    /** Locators that fail every query with [code]. */
    fun failing(code: ErrorCode): SymbolLocators = SymbolLocators(
        SymbolLocator {
            ToolResult.failure(code, "fake")
        },
        listOf(SymbolLocator { ToolResult.failure(code, "fake") }),
    )

    fun <T> ToolResult<T>.value(): T = when (this) {
        is ToolResult.Success -> value
        is ToolResult.Failure -> throw AssertionError("expected success but got $error")
    }

    fun ToolResult<*>.code(): ErrorCode = (this as? ToolResult.Failure)?.error?.code
        ?: throw AssertionError("expected a failure but got $this")
}
