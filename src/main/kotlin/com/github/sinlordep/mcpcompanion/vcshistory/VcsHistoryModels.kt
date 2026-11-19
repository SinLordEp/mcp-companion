package com.github.sinlordep.mcpcompanion.vcshistory

import com.github.sinlordep.mcpcompanion.common.diff.TextDiff
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/**
 * A committed revision as the VCS reports it: [revision] is its number as the VCS writes it (a Git hash), [author] the
 * author's name as recorded, [date] in epoch milliseconds, [message] the commit message. Any of the last three may be
 * unknown.
 */
data class CommitInfo(val revision: String, val author: String?, val date: Long?, val message: String?)

/** The newest revisions of [path] in [vcs] (e.g. "Git"), newest first; [hasMore] when the history goes on. */
data class FileLog(val path: String, val vcs: String, val revisions: List<CommitInfo>, val hasMore: Boolean)

/**
 * Who last changed each line of [path], as the VCS annotates it. [lines] holds one revision number per line (null:
 * not committed); [commits] describes each of them. Line numbers are those of [annotatedRevision]'s version; when
 * [locallyModified], the working copy differs from it.
 */
data class FileBlame(
    val path: String,
    val vcs: String,
    val annotatedRevision: String?,
    val locallyModified: Boolean,
    val lines: List<String?>,
    val commits: Map<String, CommitInfo>,
)

/**
 * The diff of one file between [fromRevision] and [toRevision] (null: the working copy, unsaved editor changes
 * included).
 */
data class FileDiff(
    val path: String,
    val vcs: String,
    val fromRevision: String,
    val toRevision: String?,
    val diff: TextDiff,
)

/**
 * Gateway boundary: the IDE's VCS integration (the VCS of the file's root: Git, Mercurial, Subversion, Perforce,
 * ...), read only. Runs the VCS's own commands in the background, cancellable; never opens a dialog.
 */
interface VcsHistoryGateway {

    /** The newest revisions of [path] (a file, or a directory when the VCS supports that), at most [max]. */
    suspend fun log(path: String, max: Int): ToolResult<FileLog>

    /** The annotation (blame) of the file [path]. */
    suspend fun blame(path: String): ToolResult<FileBlame>

    /**
     * The diff of the file [path] from revision [from] (null: the revision the working copy is based on) to [to]
     * (null: the working copy), at most [maxLines] lines.
     */
    suspend fun diff(path: String, from: String?, to: String?, maxLines: Int): ToolResult<FileDiff>
}
