package com.github.sinlordep.mcpcompanion.common.symbols

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

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

    companion object {
        private const val HOW =
            "Pass either path, line and column, or qualifiedName (e.g. com.example.Foo or com.example.Foo#bar)."

        /**
         * Validates client input that names a symbol in exactly one of the two ways: all of [path], [line] and
         * [column], or [qualifiedName]. Blank strings count as absent.
         */
        fun of(path: String?, line: Int?, column: Int?, qualifiedName: String?): ToolResult<SymbolQuery> {
            val file = path?.trim()?.takeIf { it.isNotEmpty() }
            val name = qualifiedName?.trim()?.takeIf { it.isNotEmpty() }
            val anyPosition = file != null || line != null || column != null
            return when {
                name != null && anyPosition ->
                    ToolResult.failure(
                        ErrorCode.INVALID_ARGUMENT,
                        "Both a position and a qualifiedName were given.",
                        HOW,
                    )

                name != null -> ToolResult.success(ByQualifiedName(name))

                anyPosition -> position(file, line, column)

                else -> ToolResult.failure(ErrorCode.INVALID_ARGUMENT, "No symbol was given.", HOW)
            }
        }

        private fun position(file: String?, line: Int?, column: Int?): ToolResult<SymbolQuery> =
            if (file != null && line != null && column != null) {
                ToolResult.success(AtPosition(file, line, column))
            } else {
                ToolResult.failure(ErrorCode.INVALID_ARGUMENT, "path, line and column must be given together.", HOW)
            }
    }
}
