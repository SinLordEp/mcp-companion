package com.github.sinlordep.mcpcompanion.common.source

import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocation

/**
 * A place in source code, e.g. where a symbol is used.
 *
 * [lineText] is the line at [location], trimmed and shortened by [LineExcerpt]. [enclosingDeclaration] names the
 * declarations around the place from the outside in, e.g. `Greeter.twice`; null when the place is outside any.
 */
data class CodeSite(val location: SymbolLocation, val lineText: String, val enclosingDeclaration: String?)
