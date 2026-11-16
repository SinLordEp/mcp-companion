package com.github.sinlordep.mcpcompanion.common.symbols

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/** Failures about symbols that several tools report the same way. */
object SymbolFailures {

    /** The located symbol disappeared before the tool could use it, e.g. because the code was edited meanwhile. */
    fun gone(): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.NOT_FOUND,
        "The symbol no longer exists; the code changed while the tool ran.",
        "Locate the symbol again and retry.",
    )
}
