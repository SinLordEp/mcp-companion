package com.github.sinlordep.mcpcompanion.common.symbols

/** How a client identifies a symbol. Each kind is served by its own [SymbolLocator] strategy. */
sealed interface SymbolQuery {

    /**
     * The symbol at, or referenced at, a position. [path] is project-relative or absolute; [line] and [column] are
     * 1-based, as editors show them.
     */
    data class AtPosition(val path: String, val line: Int, val column: Int) : SymbolQuery

    /**
     * A symbol by fully qualified name: `com.example.Foo`, `com.example.Outer.Inner`, or a member as
     * `com.example.Foo#bar`.
     */
    data class ByQualifiedName(val qualifiedName: String) : SymbolQuery
}
