package com.github.sinlordep.mcpcompanion.toolsets.safedelete

import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolQuery
import com.github.sinlordep.mcpcompanion.safedelete.SafeDeleteRequest
import com.github.sinlordep.mcpcompanion.safedelete.psi.safeDeleteFor
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.github.sinlordep.mcpcompanion.toolsets.symbols.ToolParameters
import com.github.sinlordep.mcpcompanion.toolsets.toMcpOutput
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext

/** MCP adapter for `mc_safe_delete`. */
class SafeDeleteToolset : CompanionToolset {

    override val toolsetId: String = ID

    @McpTool(name = "mc_safe_delete")
    @McpDescription(
        description = "Deletes a declaration only if nothing depends on it, like Safe Delete but never asking " +
            "anything. action=preview (default) changes nothing and reports canDelete, alsoDeleted and the " +
            "blockers: references that would break, dependent declarations and language conflicts (when hasMore " +
            "is true, call again with offset=nextOffset). action=apply checks again and deletes only without " +
            "blockers, as one undoable IDE command; otherwise it fails with CONFLICT. Rules: imports of the symbol " +
            "go with it (cleanups); unused methods overriding or implementing a deleted method go with it " +
            "(alsoDeleted); a method that itself overrides or implements another is refused (delete the topmost " +
            "one); a field's getter and setter are not deleted along and block until removed; parameters are " +
            "UNSUPPORTED; a class alone in its file is deleted with the file. Comments and strings are not searched.",
    )
    @Suppress("LongParameterList") // One parameter per MCP argument.
    suspend fun safeDelete(
        @McpDescription("preview (default) or apply.") action: SafeDeleteActionInput = SafeDeleteActionInput.PREVIEW,
        @McpDescription(ToolParameters.PATH) path: String? = null,
        @McpDescription(ToolParameters.LINE) line: Int? = null,
        @McpDescription(ToolParameters.COLUMN) column: Int? = null,
        @McpDescription(ToolParameters.QUALIFIED_NAME) qualifiedName: String? = null,
        @McpDescription(ToolParameters.LIMIT + " Blockers per preview page.") limit: Int? = null,
        @McpDescription(ToolParameters.OFFSET) offset: Int? = null,
    ): SafeDeleteOutput {
        val project = currentCoroutineContext().project
        return SymbolQuery.of(path, line, column, qualifiedName)
            .flatMap { symbol -> PageRequest.of(limit, offset).map { SafeDeleteRequest(symbol, it) } }
            .flatMap { safeDeleteFor(project).execute(action.action, it) }
            .map(SafeDeleteOutput::from)
            .toMcpOutput()
    }

    companion object {
        const val ID: String = "safe_delete"
    }
}
