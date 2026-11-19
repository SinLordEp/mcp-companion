package com.github.sinlordep.mcpcompanion.vcshistory.vcs

import com.github.sinlordep.mcpcompanion.common.diff.UnifiedDiff
import com.github.sinlordep.mcpcompanion.common.progress.ide.runUnderIndicator
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vcs.history.VcsHistoryProvider
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil

/**
 * A file's text in committed revisions, taken from its own history (`VcsFileRevision.loadContent`): a revision is
 * accepted only when it is one of the file's newest [MAX_REVISIONS] revisions (by full number or a prefix of at least
 * [MIN_PREFIX] characters). Not through `DiffProvider.createFileContent`: Git's opens a modal error dialog for an
 * unknown revision (seen end to end), and an unknown revision cannot be told from one without the file.
 */
internal class RevisionContents(private val vcs: AbstractVcs, private val provider: VcsHistoryProvider) {

    /** The file's revisions, newest first; loaded once per call. */
    suspend fun revisionsOf(file: VirtualFile): List<VcsFileRevision> =
        HistoryCollector(MAX_REVISIONS).collect(provider, VcsUtil.getFilePath(file)).revisions

    /** The revision of [revisions] that [name] means. */
    fun find(revisions: List<VcsFileRevision>, name: String, path: String): VcsFileRevision {
        val exact = revisions.firstOrNull { it.revisionNumber.asString() == name }
        if (exact != null) return exact
        val byPrefix = revisions.filter { name.length >= MIN_PREFIX && it.revisionNumber.asString().startsWith(name) }
        return byPrefix.singleOrNull() ?: throw VcsException(
            if (byPrefix.isEmpty()) {
                "'$name' is not one of the newest $MAX_REVISIONS ${vcs.displayName} revisions of $path."
            } else {
                "'$name' matches ${byPrefix.size} revisions of $path; give more characters."
            },
        )
    }

    /** The text of [file] in [revision], line breaks as `\n`. */
    suspend fun textOf(file: VirtualFile, revision: VcsFileRevision): String = runUnderIndicator {
        val bytes = revision.loadContent()
            ?: throw VcsException(
                "${vcs.displayName} has no content of the file in ${revision.revisionNumber.asString()}.",
            )
        UnifiedDiff.normalised(String(bytes, file.charset))
    }

    companion object {
        const val MAX_REVISIONS = 1_000
        const val MIN_PREFIX = 4
    }
}
