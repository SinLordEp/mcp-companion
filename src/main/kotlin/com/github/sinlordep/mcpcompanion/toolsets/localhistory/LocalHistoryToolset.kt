package com.github.sinlordep.mcpcompanion.toolsets.localhistory

import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.localhistory.LocalHistoryArguments
import com.github.sinlordep.mcpcompanion.localhistory.ide.localHistoryControlFor
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.github.sinlordep.mcpcompanion.toolsets.toMcpOutput
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext

/** MCP adapter for `mc_local_history`. */
class LocalHistoryToolset : CompanionToolset {

    override val toolsetId: String = ID

    @McpTool(name = "mc_local_history")
    @McpToolHints(
        readOnlyHint = McpToolHintValue.FALSE,
        destructiveHint = McpToolHintValue.TRUE,
        idempotentHint = McpToolHintValue.FALSE,
        openWorldHint = McpToolHintValue.FALSE,
    )
    @McpDescription(description = DESCRIPTION)
    suspend fun localHistory(
        @McpDescription(LocalHistoryParameterTexts.ACTION) action: LocalHistoryActionInput,
        @McpDescription(LocalHistoryParameterTexts.PATH) path: String? = null,
        @McpDescription(LocalHistoryParameterTexts.NAME) name: String? = null,
        @McpDescription(LocalHistoryParameterTexts.LABEL) label: String? = null,
        @McpDescription(LocalHistoryParameterTexts.TIME) time: String? = null,
        @McpDescription(LocalHistoryParameterTexts.MAX_LINES) maxLines: Int? = null,
    ): LocalHistoryOutput {
        val project = currentCoroutineContext().project
        val arguments = LocalHistoryArguments(path, name, label, time, maxLines)
        return localHistoryControlFor(project).execute(action.action, arguments).map(LocalHistoryOutput::from)
            .toMcpOutput()
    }

    companion object {
        const val ID: String = "local_history"

        private const val DESCRIPTION: String = "The IDE's Local History (saved versions of project files, kept a " +
            "few days, independent of VCS): a safety net for edits. Put a label before a large or risky change; " +
            "if it goes wrong, compare with it and revert to it. action=label name=<text>: puts a label (also " +
            "visible in the IDE's Local History). action=labels: the labels this tool put that the IDE still " +
            "keeps, newest first (labels made in the IDE itself are not listed; use time for them). " +
            "action=diff: what changed in path since a past state, as unified diffs (then -> now). " +
            "action=preview: what revert would change (now -> then); changes nothing. action=revert: a file gets " +
            "the content it had then, as one undoable IDE command (Edit | Undo); a directory is reverted to a " +
            "label by the IDE's own revert (files created since are deleted, deleted ones come back); a directory " +
            "cannot be reverted to a time. diff, preview and revert take path (file or directory, \".\" = " +
            "project) and exactly one of label (the newest label of this tool with that name) or time (ISO-8601; " +
            "without an offset in the IDE's time zone; the newest version saved at or before it). Directory " +
            "diffs list changed and created files, not files deleted since. change=no_earlier_version (time, or a " +
            "label of an earlier IDE session): the file changed since then but the history has no older version " +
            "of it to compare with - label before changing. maxLines caps all diffs together " +
            "(default 400, max 5000; truncated/omitted say what was cut). Unsaved editor changes count as the " +
            "current state. Read-only files: CONFLICT, nothing changed. Project files only; Local History " +
            "disabled: UNSUPPORTED."
    }
}

/** Parameter descriptions of `mc_local_history`: prompts for the agent, so short. */
private object LocalHistoryParameterTexts {
    const val ACTION = "label, labels, diff, preview or revert."
    const val PATH = "diff, preview, revert: file or directory, relative to the project root (\".\" = project)."
    const val NAME = "label: the label's name, e.g. \"before rewriting OrderService\"."
    const val LABEL = "diff, preview, revert: the newest label of this tool with this name."
    const val TIME = "diff, preview, revert: the state at this time, e.g. 2026-11-19T10:15:00."
    const val MAX_LINES = "diff, preview: diff lines in all, 1-5000, default 400."
}
