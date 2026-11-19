package com.github.sinlordep.mcpcompanion.localhistory.ide

import com.github.sinlordep.mcpcompanion.common.diff.UnifiedDiff
import com.github.sinlordep.mcpcompanion.common.diff.ide.TextComparison
import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.localhistory.DiffDirection
import com.github.sinlordep.mcpcompanion.localhistory.FileChangeKind
import com.github.sinlordep.mcpcompanion.localhistory.HistoryComparison
import com.github.sinlordep.mcpcompanion.localhistory.HistoryFileChange
import com.github.sinlordep.mcpcompanion.localhistory.HistoryTarget
import com.github.sinlordep.mcpcompanion.localhistory.pointInTime
import com.intellij.history.Label
import com.intellij.history.LocalHistory
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The versions of project files in the Local History, compared with what they contain now. At a label put in this
 * IDE session: `Label.getByteContent(path)`, the state just before the label (the history reverted back to it; no
 * content means the file did not exist). At a time, or at a label of an earlier session (whose label object is gone):
 * `LocalHistory.getByteContent(file, timestamp condition)`, the newest saved version at or before the time; it finds
 * no version when the file was not changed in the IDE before the time ([FileChangeKind.NO_EARLIER_VERSION]).
 * Unsaved editor changes are the current version. A directory is compared file by file: only files changed after the
 * time (newer modification stamp, or unsaved changes) can differ, and files deleted since are not seen (the public API
 * cannot list them).
 */
internal class FileVersions(
    private val project: Project,
    private val files: ProjectFiles,
    private val history: LocalHistory,
    private val liveLabel: (String) -> Label?,
) {

    /** A file's text (line breaks as `\n`), or only the fact that it is binary. */
    class Version(val text: String, val binary: Boolean)

    /** Whether the target can tell that a file without a version did not exist then. */
    private fun HistoryTarget.knowsAbsence(): Boolean = this is HistoryTarget.AtLabel && liveLabel(label.id) != null

    /** What [file] contained at [target]; null when there is no such version. */
    suspend fun textAt(file: VirtualFile, target: HistoryTarget): Version? {
        val label = (target as? HistoryTarget.AtLabel)?.let { liveLabel(it.label.id) }
        val bytes = withContext(Dispatchers.IO) {
            if (label != null) {
                label.getByteContent(file.path)?.bytes
            } else {
                val time = target.pointInTime
                history.getByteContent(file) { it <= time }
            }
        } ?: return null
        if (file.fileType.isBinary) return Version("", binary = true)
        val bom = file.bom
        val text = if (bom != null && bytes.startsWithBom(bom)) {
            String(bytes, bom.size, bytes.size - bom.size, file.charset)
        } else {
            String(bytes, file.charset)
        }
        return Version(UnifiedDiff.normalised(text), binary = false)
    }

    suspend fun compare(
        root: VirtualFile,
        target: HistoryTarget,
        direction: DiffDirection,
        maxLines: Int,
    ): HistoryComparison {
        val (candidates, scanned, complete) = readAction { candidates(root, target.pointInTime) }
        var budget = maxLines
        val changes = candidates.mapNotNull { file ->
            val then = textAt(file, target)
            val path = files.pathOf(file)
            val binary = file.fileType.isBinary
            val now = if (binary) null else readAction { currentText(file) }
            val kind = kindOf(then != null, target.knowsAbsence(), direction)
            when {
                binary -> HistoryFileChange(path, kind, binary = true, diff = null, omitted = false)

                then != null && then.text == now -> null

                kind == FileChangeKind.NO_EARLIER_VERSION ->
                    HistoryFileChange(path, kind, binary = false, diff = null, omitted = false)

                budget <= 0 -> HistoryFileChange(path, kind, binary = false, diff = null, omitted = true)

                else -> {
                    val (old, new) = if (direction == DiffDirection.SINCE_THEN) then?.text to now else now to then?.text
                    val diff = TextComparison.diff(path, path, old, new, budget)
                    budget -= diff.text.lines().size
                    HistoryFileChange(path, kind, binary = false, diff = diff, omitted = false)
                }
            }
        }
        return HistoryComparison(files.pathOf(root), changes, scanned, complete)
    }

    private data class Candidates(val files: List<VirtualFile>, val scanned: Int, val complete: Boolean)

    /** Files under [root] that may differ from their state at [epochMillis]. */
    private fun candidates(root: VirtualFile, epochMillis: Long): Candidates {
        val documents = FileDocumentManager.getInstance()
        fun changed(file: VirtualFile) = file.timeStamp > epochMillis ||
            documents.getCachedDocument(file)?.let(documents::isDocumentUnsaved) == true
        if (!root.isDirectory) return Candidates(listOf(root).filter(::changed), 1, true)
        val found = ArrayList<VirtualFile>()
        var scanned = 0
        val complete = ProjectFileIndex.getInstance(project).iterateContentUnderDirectory(root) { file ->
            if (!file.isDirectory) {
                scanned++
                if (changed(file)) found += file
            }
            scanned < MAX_SCANNED
        }
        return Candidates(found.sortedBy { it.path }, scanned, complete)
    }

    private fun currentText(file: VirtualFile): String? {
        if (!file.isValid) return null
        val text = FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: LoadTextUtil.loadText(file)
        return UnifiedDiff.normalised(text)
    }

    private fun kindOf(existedThen: Boolean, knowsAbsence: Boolean, direction: DiffDirection): FileChangeKind = when {
        existedThen -> FileChangeKind.MODIFIED
        !knowsAbsence -> FileChangeKind.NO_EARLIER_VERSION
        direction == DiffDirection.SINCE_THEN -> FileChangeKind.CREATED
        else -> FileChangeKind.DELETED
    }

    private fun ByteArray.startsWithBom(bom: ByteArray): Boolean =
        size >= bom.size && bom.indices.all { this[it] == bom[it] }

    private companion object {
        /** Files of a directory compared at most per call. */
        const val MAX_SCANNED = 20_000
    }
}
