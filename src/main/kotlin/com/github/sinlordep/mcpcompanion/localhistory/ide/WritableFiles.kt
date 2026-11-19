package com.github.sinlordep.mcpcompanion.localhistory.ide

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files

/**
 * What a revert needs before it writes, so that the IDE never asks: every file it may write is writable (else nothing
 * is written), and documents with unsaved changes are saved (else the IDE would ask which version to keep).
 */
internal class WritableFiles(private val files: ProjectFiles) {

    /** A refusal naming the read-only files under [root] (or [root] itself), or null. Call in a read action. */
    fun readOnlyUnder(root: VirtualFile): ToolResult<Nothing>? {
        val locked = underneath(root).filterNot(::writable)
        if (locked.isEmpty()) return null
        return ToolResult.failure(
            ErrorCode.CONFLICT,
            "Read-only files would change: ${locked.take(MAX_LISTED).joinToString { files.pathOf(it) }}. " +
                "Nothing was changed.",
            "Make them writable (or check them out), then retry.",
        )
    }

    /** Saves the unsaved documents of the files under [root]. Call on the EDT. */
    fun saveDocumentsUnder(root: VirtualFile) {
        val documents = FileDocumentManager.getInstance()
        val unsaved = documents.unsavedDocuments.filter { document ->
            documents.getFile(document)?.let { VfsUtilCore.isAncestor(root, it, false) } == true
        }
        WriteAction.run<RuntimeException> { unsaved.forEach(documents::saveDocument) }
    }

    /**
     * Whether [file] can be written: the file system's own answer for local files, since the IDE's cached attribute
     * lags behind a change made outside the IDE until the next refresh.
     */
    private fun writable(file: VirtualFile): Boolean =
        file.isWritable && (!file.isInLocalFileSystem || Files.isWritable(file.toNioPath()))

    private fun underneath(root: VirtualFile): List<VirtualFile> {
        if (!root.isDirectory) return listOf(root)
        val found = ArrayList<VirtualFile>()
        VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
            if (!file.isDirectory) found += file
            found.size < MAX_CHECKED
        }
        return found
    }

    private companion object {
        const val MAX_CHECKED = 20_000
        const val MAX_LISTED = 10
    }
}
