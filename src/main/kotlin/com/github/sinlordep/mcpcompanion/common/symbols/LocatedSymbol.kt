package com.github.sinlordep.mcpcompanion.common.symbols

/**
 * A symbol found by a [SymbolLocator], described without platform types.
 *
 * [handle] lets a later gateway get back to the underlying platform element; use cases pass it on without looking
 * inside.
 */
class LocatedSymbol(
    val name: String,
    val qualifiedName: String?,
    val location: SymbolLocation?,
    val handle: SymbolHandle,
) {
    override fun toString(): String = "LocatedSymbol($name, $qualifiedName, $location)"
}

/** Where a symbol is declared. [path] is project-relative when inside the project; [line] and [column] are 1-based. */
data class SymbolLocation(val path: String, val line: Int, val column: Int)

/** Opaque reference to the platform element behind a [LocatedSymbol]. Implemented by gateways only. */
interface SymbolHandle
