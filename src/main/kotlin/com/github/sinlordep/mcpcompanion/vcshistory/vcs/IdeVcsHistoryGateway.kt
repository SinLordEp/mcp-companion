package com.github.sinlordep.mcpcompanion.vcshistory.vcs

import com.github.sinlordep.mcpcompanion.common.diff.ide.TextComparison
import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.progress.ide.runUnderIndicator
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.symbols.FileOrigin
import com.github.sinlordep.mcpcompanion.vcshistory.CommitInfo
import com.github.sinlordep.mcpcompanion.vcshistory.FileBlame
import com.github.sinlordep.mcpcompanion.vcshistory.FileDiff
import com.github.sinlordep.mcpcompanion.vcshistory.FileLog
import com.github.sinlordep.mcpcompanion.vcshistory.VcsHistoryGateway
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.annotate.LineAnnotationAspect
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.history.VcsRevisionDescription
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil

/**
 * `mc_vcs_history` through the IDE's VCS abstraction, for whatever VCS owns the file's root: the history provider
 * (`VcsHistoryProvider.reportAppendableHistory`, stopped once enough revisions are in, [HistoryCollector]), the
 * annotation provider (`AnnotationProvider.annotate`, what Annotate with Git Blame shows) and the revisions' own
 * contents ([RevisionContents]); the diff provider only names the working copy's base revision. No VCS command line
 * of our own;
 * the VCS integration runs its commands in the background under our indicator, so a cancelled call stops them.
 * Registered only with the platform's VCS support (`mcp-companion-vcs.xml`).
 */
internal class IdeVcsHistoryGateway(private val project: Project, private val files: ProjectFiles) :
    VcsHistoryGateway {

    private class Target(val file: VirtualFile, val path: String, val vcs: AbstractVcs)

    override suspend fun log(path: String, max: Int): ToolResult<FileLog> = withTarget(path) { target ->
        val provider = target.vcs.vcsHistoryProvider ?: return@withTarget unsupported(target, "file history")
        if (target.file.isDirectory && !provider.supportsHistoryForDirectories()) {
            return@withTarget unsupported(target, "the history of directories")
        }
        vcsFailure(target) {
            val collected = HistoryCollector(max).collect(provider, VcsUtil.getFilePath(target.file))
            FileLog(target.path, target.vcs.displayName, collected.revisions.map(::commitOf), collected.hasMore)
        }
    }

    override suspend fun blame(path: String): ToolResult<FileBlame> = withFile(path) { target ->
        val provider = target.vcs.annotationProvider ?: return@withFile unsupported(target, "annotations (blame)")
        val modified = readAction { ChangeListManager.getInstance(project).getChange(target.file) != null }
        vcsFailure(target) {
            val annotation = runUnderIndicator { provider.annotate(target.file) }
            val author = annotation.aspects.firstOrNull { it.id == LineAnnotationAspect.AUTHOR }
            val commits = LinkedHashMap<String, CommitInfo>()
            annotation.revisions.orEmpty().forEach { commits[it.revisionNumber.asString()] = commitOf(it) }
            val lines = (0 until annotation.lineCount).map { line ->
                annotation.getLineRevisionNumber(line)?.asString()?.also { revision ->
                    commits.getOrPut(revision) {
                        CommitInfo(revision, author?.getValue(line), annotation.getLineDate(line)?.time, null)
                    }
                }
            }
            FileBlame(
                path = target.path,
                vcs = target.vcs.displayName,
                annotatedRevision = annotation.currentRevision?.asString(),
                locallyModified = modified,
                lines = lines,
                commits = commits,
            )
        }
    }

    override suspend fun diff(path: String, from: String?, to: String?, maxLines: Int): ToolResult<FileDiff> =
        withFile(path) { target ->
            val history = target.vcs.vcsHistoryProvider ?: return@withFile unsupported(target, "file history")
            if (target.file.fileType.isBinary) {
                return@withFile ToolResult.failure(ErrorCode.UNSUPPORTED, "${target.path} is a binary file.")
            }
            val working = if (to == null) readAction { workingCopy(target.file) } else null
            val current = if (from ==
                null
            ) {
                runUnderIndicator { target.vcs.diffProvider?.getCurrentRevision(target.file) }
            } else {
                null
            }
            vcsFailure(target) {
                val contents = RevisionContents(target.vcs, history)
                val revisions = contents.revisionsOf(target.file)
                val fromName = from ?: current?.asString()
                    ?: throw VcsException("${target.path} has no committed revision.")
                val fromRevision = contents.find(revisions, fromName, target.path)
                val toRevision = to?.let { contents.find(revisions, it, target.path) }
                val before = contents.textOf(target.file, fromRevision)
                val after = toRevision?.let { contents.textOf(target.file, it) } ?: working
                val diff = TextComparison.diff(target.path, target.path, before, after, maxLines)
                val fromNumber = fromRevision.revisionNumber.asString()
                FileDiff(target.path, target.vcs.displayName, fromNumber, toRevision?.revisionNumber?.asString(), diff)
            }
        }

    /** The working copy's text, unsaved editor changes included. Call in a read action. */
    private fun workingCopy(file: VirtualFile): String? =
        if (file.isValid) FileDocumentManager.getInstance().getDocument(file)?.text else null

    private fun commitOf(revision: VcsRevisionDescription) = CommitInfo(
        revision = revision.revisionNumber.asString(),
        author = revision.author?.takeIf { it.isNotBlank() },
        date = revision.revisionDate?.time,
        message = revision.commitMessage?.trim()?.takeIf { it.isNotEmpty() },
    )

    private suspend fun <T> withFile(path: String, body: suspend (Target) -> ToolResult<T>): ToolResult<T> =
        withTarget(path) { target ->
            if (target.file.isDirectory) {
                ToolResult.failure(
                    ErrorCode.INVALID_ARGUMENT,
                    "${target.path} is a directory; blame and diff take one file.",
                    "Use action=log for the history of a directory.",
                )
            } else {
                body(target)
            }
        }

    private suspend fun <T> withTarget(path: String, body: suspend (Target) -> ToolResult<T>): ToolResult<T> {
        val found = readAction {
            val file = files.find(path) ?: return@readAction ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "No file or directory at $path.",
                "Pass a path relative to the project root or an absolute path.",
            )
            if (files.originOf(file) != FileOrigin.PROJECT) {
                return@readAction ToolResult.failure(
                    ErrorCode.INVALID_ARGUMENT,
                    "$path is not in the project's content.",
                )
            }
            val vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(file) ?: return@readAction ToolResult
                .failure(
                    ErrorCode.UNSUPPORTED,
                    "${files.pathOf(file)} is not under version control in this project.",
                    "Use mc_local_history for the IDE's own history of the file.",
                )
            ToolResult.success(Target(file, files.pathOf(file), vcs))
        }
        return when (found) {
            is ToolResult.Success -> body(found.value)
            is ToolResult.Failure -> found
        }
    }

    private fun unsupported(target: Target, what: String): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.UNSUPPORTED,
        "${target.vcs.displayName} does not provide $what in the IDE (${target.path}).",
    )

    private inline fun <T> vcsFailure(target: Target, body: () -> T): ToolResult<T> = try {
        ToolResult.success(body())
    } catch (e: VcsException) {
        ToolResult.failure(
            ErrorCode.NOT_FOUND,
            "${target.vcs.displayName} could not answer for ${target.path}: ${e.message}",
            "Check the revision (action=log lists them) and that the file is committed.",
        )
    }
}
