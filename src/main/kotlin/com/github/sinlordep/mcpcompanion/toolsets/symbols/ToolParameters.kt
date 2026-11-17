package com.github.sinlordep.mcpcompanion.toolsets.symbols

import com.github.sinlordep.mcpcompanion.common.scope.SearchScopeSpec

/**
 * Parameter descriptions shared by several tools. They are prompts for the AI agent, so they stay short: every word
 * is sent with the tool list in every session.
 */
object ToolParameters {
    const val PATH: String = "File with the symbol's declaration or a reference to it: relative to the project " +
        "directory (.. allowed), absolute, an archive entry (/x.jar!/pkg/Foo.java) or a file:// or jar:// URL, as " +
        "this and the official tools output them. Give path, line and column together, or qualifiedName instead."
    const val LINE: String = "1-based line of the symbol's name or of a reference to it."
    const val COLUMN: String = "1-based column inside the name."
    const val QUALIFIED_NAME: String = "Instead of a position: fully qualified name, e.g. com.example.Foo, " +
        "com.example.Outer.Inner or com.example.Foo#member (JVM languages; overloaded methods need a position)."
    const val SCOPE: String = "Where to search: " + SearchScopeSpec.SYNTAX + "."
    const val LIMIT: String = "Page size, 1-1000. Default 100."
    const val OFFSET: String = "Results to skip. Default 0; to continue, pass nextOffset of the previous page."
}
