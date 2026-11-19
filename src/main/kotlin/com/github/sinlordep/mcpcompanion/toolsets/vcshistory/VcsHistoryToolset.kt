package com.github.sinlordep.mcpcompanion.toolsets.vcshistory

import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.github.sinlordep.mcpcompanion.toolsets.toMcpOutput
import com.github.sinlordep.mcpcompanion.vcshistory.VcsHistoryArguments
import com.github.sinlordep.mcpcompanion.vcshistory.vcs.vcsHistoryControlFor
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext

/** MCP adapter for `mc_vcs_history`; registered in `mcp-companion-vcs.xml` (needs the platform's VCS support). */
class VcsHistoryToolset : CompanionToolset {

    override val toolsetId: String = ID

    @McpTool(name = "mc_vcs_history")
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
        openWorldHint = McpToolHintValue.FALSE,
    )
    @McpDescription(description = DESCRIPTION)
    @Suppress("LongParameterList") // One parameter per MCP argument.
    suspend fun vcsHistory(
        @McpDescription(VcsHistoryParameterTexts.ACTION) action: VcsHistoryActionInput,
        @McpDescription(VcsHistoryParameterTexts.PATH) path: String,
        @McpDescription(VcsHistoryParameterTexts.LINE) line: Int? = null,
        @McpDescription(VcsHistoryParameterTexts.END_LINE) endLine: Int? = null,
        @McpDescription(VcsHistoryParameterTexts.FROM) from: String? = null,
        @McpDescription(VcsHistoryParameterTexts.TO) to: String? = null,
        @McpDescription(VcsHistoryParameterTexts.MAX_LINES) maxLines: Int? = null,
        @McpDescription(VcsHistoryParameterTexts.LIMIT) limit: Int? = null,
        @McpDescription(VcsHistoryParameterTexts.OFFSET) offset: Int? = null,
    ): VcsHistoryOutput {
        val project = currentCoroutineContext().project
        val arguments = VcsHistoryArguments(path, line, endLine, from, to, maxLines, limit, offset)
        return vcsHistoryControlFor(project).execute(action.action, arguments).map(VcsHistoryOutput::from)
            .toMcpOutput()
    }

    companion object {
        const val ID: String = "vcs_history"

        private const val DESCRIPTION: String = "Committed history of project files through the IDE's VCS " +
            "integration (Git, Mercurial, Subversion, Perforce, ...), read only: where code came from and why. " +
            "action=log: revisions of path (a file, or a directory when the VCS supports it), newest first: " +
            "revision, author, date, summary (first line), message (the whole one when longer, cut at 1000 " +
            "characters); limit " +
            "(default 20, max 200)/offset; with line (and endLine) only the commits that last changed those lines " +
            "(from the annotation, not every commit that ever touched them). action=blame: who last changed each " +
            "line of a file, as segments startLine-endLine -> revision, with the commits they name; line/endLine " +
            "for a range; limit (default 100)/offset. Line numbers are those of annotatedRevision; " +
            "locallyModified=true means the working copy differs (see diff). action=diff: unified diff of one " +
            "file from revision `from` (default: the revision the working copy is based on, e.g. HEAD) to `to` " +
            "(default: the working copy, unsaved editor changes included); maxLines (default 400, max 5000; " +
            "truncated). from/to are revisions of the file as log lists them (full, or a unique prefix of 4+ " +
            "characters; among its newest 1000). Authors are " +
            "reported as recorded. Not under version control: UNSUPPORTED; unknown revision: NOT_FOUND. For " +
            "uncommitted history use mc_local_history."
    }
}

/** Parameter descriptions of `mc_vcs_history`: prompts for the agent, so short. */
private object VcsHistoryParameterTexts {
    const val ACTION = "log, blame or diff."
    const val PATH = "File (or, for log, directory), relative to the project root or absolute."
    const val LINE = "log, blame: first 1-based line of a range."
    const val END_LINE = "log, blame: last line of the range, with line; default line (blame: the file's end)."
    const val FROM = "diff: older revision of the file (from log; a prefix works); default the working copy's base."
    const val TO = "diff: newer revision, with from; default the working copy."
    const val MAX_LINES = "diff: lines of diff, 1-5000, default 400."
    const val LIMIT = "log: commits per page (default 20, max 200); blame: segments (default 100, max 1000)."
    const val OFFSET = "log, blame: first item of the page, default 0; continue with nextOffset."
}
