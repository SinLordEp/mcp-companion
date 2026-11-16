package com.github.sinlordep.mcpcompanion.safedelete

import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolHandle

/** Gateway boundary of `safe delete`: the IDE's safe-delete machinery. */
interface SafeDeleteGateway {

    /** Finds everything that speaks against deleting [target], and what the deletion would clean up. */
    suspend fun analyse(target: SymbolHandle): ToolResult<SafeDeleteAnalysis>

    /**
     * Deletes the analysed symbol as one undoable command, unless the code changed since [plan] was made. Callers
     * must only pass plans without blockers.
     */
    suspend fun delete(plan: DeletionPlan): ToolResult<DeletionAttempt>
}
