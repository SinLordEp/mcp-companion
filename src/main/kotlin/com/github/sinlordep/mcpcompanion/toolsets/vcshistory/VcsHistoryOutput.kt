package com.github.sinlordep.mcpcompanion.toolsets.vcshistory

import com.github.sinlordep.mcpcompanion.toolsets.TimeTexts
import com.github.sinlordep.mcpcompanion.vcshistory.BlameSegment
import com.github.sinlordep.mcpcompanion.vcshistory.CommitInfo
import com.github.sinlordep.mcpcompanion.vcshistory.VcsHistoryAction
import com.github.sinlordep.mcpcompanion.vcshistory.VcsHistoryOutcome
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** MCP input values of `action`. */
@Serializable
enum class VcsHistoryActionInput(val action: VcsHistoryAction) {
    @SerialName("log")
    LOG(VcsHistoryAction.LOG),

    @SerialName("blame")
    BLAME(VcsHistoryAction.BLAME),

    @SerialName("diff")
    DIFF(VcsHistoryAction.DIFF),
}

/**
 * MCP output model of `mc_vcs_history`. `log`: [commits] (newest first); `blame`: [segments] and the [commits] they
 * name; `diff`: [from], [to] and [diff]. [note] warns when line numbers may not match the working copy.
 */
@Serializable
data class VcsHistoryOutput(
    val path: String,
    val vcs: String,
    val commits: List<CommitOutput>? = null,
    val line: Int? = null,
    val endLine: Int? = null,
    val segments: List<SegmentOutput>? = null,
    val annotatedRevision: String? = null,
    val locallyModified: Boolean? = null,
    val from: String? = null,
    val to: String? = null,
    val diff: String? = null,
    val added: Int? = null,
    val removed: Int? = null,
    val truncated: Boolean? = null,
    val hasMore: Boolean? = null,
    val nextOffset: Int? = null,
    val note: String? = null,
) {
    companion object {
        fun from(outcome: VcsHistoryOutcome): VcsHistoryOutput = when (outcome) {
            is VcsHistoryOutcome.Logged -> VcsHistoryOutput(
                path = outcome.path,
                vcs = outcome.vcs,
                commits = outcome.revisions.items.map(CommitOutput::from),
                line = outcome.lines?.first,
                endLine = outcome.lines?.last,
                hasMore = outcome.revisions.hasMore,
                nextOffset = outcome.revisions.nextOffset,
                note = outcome.lines?.let { BY_LINES },
            )

            is VcsHistoryOutcome.Blamed -> VcsHistoryOutput(
                path = outcome.blame.path,
                vcs = outcome.blame.vcs,
                commits = outcome.commits.map(CommitOutput::from),
                line = outcome.lines.first.takeUnless { outcome.lines.isEmpty() },
                endLine = outcome.lines.last.takeUnless { outcome.lines.isEmpty() },
                segments = outcome.segments.items.map(SegmentOutput::from),
                annotatedRevision = outcome.blame.annotatedRevision,
                locallyModified = outcome.blame.locallyModified,
                hasMore = outcome.segments.hasMore,
                nextOffset = outcome.segments.nextOffset,
                note = LOCAL_CHANGES.takeIf { outcome.blame.locallyModified },
            )

            is VcsHistoryOutcome.Diffed -> VcsHistoryOutput(
                path = outcome.diff.path,
                vcs = outcome.diff.vcs,
                from = outcome.diff.fromRevision,
                to = outcome.diff.toRevision ?: WORKING_COPY,
                diff = outcome.diff.diff.text,
                added = outcome.diff.diff.added,
                removed = outcome.diff.diff.removed,
                truncated = outcome.diff.diff.truncated.takeIf { it },
                note = "No differences.".takeIf { outcome.diff.diff.isEmpty },
            )
        }

        private const val WORKING_COPY = "working copy"
        private const val BY_LINES = "The commits that last changed these lines (from the annotation), not every " +
            "commit that ever touched them."
        private const val LOCAL_CHANGES = "The file has uncommitted changes: line numbers are those of " +
            "annotatedRevision, not of the working copy (action=diff shows the local changes)."
    }
}

/** A commit as the VCS reports it; [summary] is the first line of [message], [date] ISO-8601. */
@Serializable
data class CommitOutput(
    val revision: String,
    val author: String? = null,
    val date: String? = null,
    val summary: String? = null,
    val message: String? = null,
) {
    companion object {
        fun from(commit: CommitInfo): CommitOutput {
            val message = commit.message
            val summary = message?.lineSequence()?.firstOrNull()?.trim()
            return CommitOutput(
                revision = commit.revision,
                author = commit.author,
                date = commit.date?.let(TimeTexts::iso),
                summary = summary,
                message = message?.takeIf { it != summary }?.let(::shortened),
            )
        }

        private fun shortened(text: String): String =
            if (text.length <= MAX_MESSAGE) text else text.take(MAX_MESSAGE - 1) + "…"

        private const val MAX_MESSAGE = 1_000
    }
}

/** Lines [startLine]..[endLine] (1-based) last changed in [revision]; no revision: not committed. */
@Serializable
data class SegmentOutput(val startLine: Int, val endLine: Int, val revision: String? = null) {
    companion object {
        fun from(segment: BlameSegment) = SegmentOutput(segment.startLine, segment.endLine, segment.revision)
    }
}
