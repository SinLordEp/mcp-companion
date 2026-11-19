package com.github.sinlordep.mcpcompanion.toolsets.quickfix

import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.quickfix.QuickFixArguments
import com.github.sinlordep.mcpcompanion.quickfix.psi.quickFixControlFor
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.github.sinlordep.mcpcompanion.toolsets.toMcpOutput
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext

/** MCP adapter for `mc_quick_fix`. */
class QuickFixToolset : CompanionToolset {

    override val toolsetId: String = ID

    @McpTool(name = "mc_quick_fix")
    @McpToolHints(
        readOnlyHint = McpToolHintValue.FALSE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.FALSE,
        openWorldHint = McpToolHintValue.FALSE,
    )
    @McpDescription(description = DESCRIPTION)
    @Suppress("LongParameterList") // One parameter per MCP argument.
    suspend fun quickFix(
        @McpDescription(QuickFixParameterTexts.ACTION) action: QuickFixActionInput? = null,
        @McpDescription(QuickFixParameterTexts.PATH) path: String? = null,
        @McpDescription(QuickFixParameterTexts.LINE) line: Int? = null,
        @McpDescription(QuickFixParameterTexts.END_LINE) endLine: Int? = null,
        @McpDescription(QuickFixParameterTexts.MIN_SEVERITY) minSeverity: QuickFixSeverityInput? = null,
        @McpDescription(QuickFixParameterTexts.FIX) fix: String? = null,
        @McpDescription(QuickFixParameterTexts.LIMIT) limit: Int? = null,
        @McpDescription(QuickFixParameterTexts.OFFSET) offset: Int? = null,
    ): QuickFixOutput {
        val project = currentCoroutineContext().project
        val arguments = QuickFixArguments(
            path = path,
            line = line,
            endLine = endLine,
            minSeverity = minSeverity?.severity,
            fix = fix,
            limit = limit,
            offset = offset,
        )
        val chosen = (action ?: QuickFixActionInput.LIST).action
        return quickFixControlFor(project).execute(chosen, arguments).map(QuickFixOutput::from).toMcpOutput()
    }

    companion object {
        const val ID: String = "quick_fix"

        private const val DESCRIPTION: String = "The IDE's quick fixes (what Alt+Enter offers on a problem: add an " +
            "import, add throws or try/catch, make a class abstract, change a type, ...) for the problems of one " +
            "project file, without opening it. action=list (default): the file's problems (optionally those " +
            "touching line..endLine; minSeverity warning (default, includes weak warnings) or error), one page " +
            "(limit default 20, max 100; offset/nextOffset), each with line, column, severity, message, lineText and " +
            "its fixes: id, name, applicable, and reason when not. A fix that lets the user choose (e.g. which " +
            "class to import) is listed once per choice. Messages and names are in the IDE's language. Ids are " +
            "valid for the file's current content only. " +
            "action=apply fix=<id> path=<file>: analyses again and applies exactly that fix as one undoable IDE " +
            "command (Edit | Undo), returning changedFiles. Refused, nothing changed: an id that no longer matches " +
            "(the file changed or the problem is gone: CONFLICT, list again); a fix that needs the user - it opens " +
            "a dialog or a popup, asks for template input, creates files, or works only in an open editor (e.g. " +
            "Implement methods, Create class): UNSUPPORTED with the reason. Library, SDK and read-only files are " +
            "refused. One fix per call; no fix-all. Typical: mc_diagnostics -> mc_quick_fix list (path, line) -> " +
            "apply -> mc_diagnostics again. The analysis is the editor's (language checks and enabled " +
            "inspections, external linters excluded)."
    }
}

/** Parameter descriptions of `mc_quick_fix`: prompts for the agent, so short. */
private object QuickFixParameterTexts {
    const val ACTION = "list (default) or apply."
    const val PATH = "The file, relative to the project root or absolute."
    const val LINE = "list: only problems touching this 1-based line (or line..endLine)."
    const val END_LINE = "list: last line of the range, with line."
    const val MIN_SEVERITY = "list: warning (default; warnings, weak warnings and errors) or error."
    const val FIX = "apply: a fix id from list, e.g. 12:9/2-3fa1b2c4."
    const val LIMIT = "list: problems per page, 1-100, default 20."
    const val OFFSET = "list: first problem of the page, default 0; continue with nextOffset."
}
