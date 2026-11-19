package com.github.sinlordep.mcpcompanion.localhistory.ide

import com.github.sinlordep.mcpcompanion.McpCompanionBundle
import com.github.sinlordep.mcpcompanion.common.edits.psi.UndoableCommand
import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.symbols.FileOrigin
import com.github.sinlordep.mcpcompanion.localhistory.DiffDirection
import com.github.sinlordep.mcpcompanion.localhistory.HistoryComparison
import com.github.sinlordep.mcpcompanion.localhistory.HistoryLabel
import com.github.sinlordep.mcpcompanion.localhistory.HistoryTarget
import com.github.sinlordep.mcpcompanion.localhistory.LocalHistoryGateway
import com.github.sinlordep.mcpcompanion.localhistory.RevertAttempt
import com.intellij.history.Label
import com.intellij.history.LocalHistory
import com.intellij.history.LocalHistoryException
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `mc_local_history` on the IDE's Local History through its public API only (`com.intellij.history.LocalHistory`):
 * `putSystemLabel`, `isLabelValid`, `getByteContent(file, timestamp condition)` (the content a file had at a time) and
 * `revertToLabel` (the IDE's own revert of a file or directory to a label). The history's list of change sets lives in
 * the platform's internal implementation and is not used (docs/notes-step10.md), so the labels this tool put are
 * remembered by [RecordedLabels]. Nothing asks the user: read-only files are refused before anything is written, and
 * documents with unsaved changes are saved before the IDE's revert writes the files (otherwise it would ask which
 * version to keep).
 */
internal class IdeLocalHistoryGateway(
    private val project: Project,
    private val files: ProjectFiles,
    private val clock: () -> Long = System::currentTimeMillis,
) : LocalHistoryGateway {

    private val history get() = LocalHistory.getInstance()
    private val labels get() = RecordedLabels.getInstance(project)
    private val writes = WritableFiles(files)

    override suspend fun putLabel(name: String): ToolResult<HistoryLabel> {
        if (!history.isEnabled) return disabled()
        val label = history.putSystemLabel(project, name)
        if (label === Label.NULL_INSTANCE) return disabled()
        return HistoryLabel(label.id, name, clock()).also { labels.add(it, label) }.let { ToolResult.success(it) }
    }

    override suspend fun labels(): List<HistoryLabel> {
        if (!history.isEnabled) return emptyList()
        val valid = labels.all().filter { history.isLabelValid(project, it.id) }
        labels.retain(valid.map { it.id }.toSet())
        return valid
    }

    override suspend fun compare(
        path: String,
        target: HistoryTarget,
        direction: DiffDirection,
        maxLines: Int,
    ): ToolResult<HistoryComparison> = withTarget(path) { file ->
        ToolResult.success(versions().compare(file, target, direction, maxLines))
    }

    override suspend fun restoreFile(
        path: String,
        target: HistoryTarget,
        description: String,
    ): ToolResult<RevertAttempt> = withTarget(path) { file ->
        val version = versions().textAt(file, target)
            ?: return@withTarget ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "Local History has no version of ${files.pathOf(file)} from then: it was created later, or (for a " +
                    "time) it was not changed in the IDE before that time.",
                "Delete the file if it should not exist, or put a label before the next change.",
            )
        if (version.binary) {
            return@withTarget ToolResult.failure(ErrorCode.UNSUPPORTED, "${files.pathOf(file)} is a binary file.")
        }
        readAction { writes.readOnlyUnder(file) }?.let { return@withTarget it }
        val command = McpCompanionBundle.message("command.local.history.revert", files.pathOf(file), description)
        withContext(Dispatchers.EDT) {
            val document = FileDocumentManager.getInstance().getDocument(file)
            when {
                document == null -> ToolResult.failure(ErrorCode.UNSUPPORTED, "${files.pathOf(file)} is not text.")

                document.text == version.text -> ToolResult.success(RevertAttempt.NothingToRevert)

                else -> {
                    UndoableCommand(project).run(command) {
                        WriteAction.run<RuntimeException> { document.setText(version.text) }
                    }
                    ToolResult.success(RevertAttempt.Reverted(command, listOf(files.pathOf(file))))
                }
            }
        }
    }

    override suspend fun revertToLabel(path: String, label: HistoryLabel): ToolResult<RevertAttempt> =
        withTarget(path) { target ->
            readAction { writes.readOnlyUnder(target) }?.let { return@withTarget it }
            withContext(Dispatchers.EDT) { writes.saveDocumentsUnder(target) }
            try {
                history.revertToLabel(project, label.id, target)
                ToolResult.success(RevertAttempt.Reverted(null, null))
            } catch (e: LocalHistoryException) {
                ToolResult.failure(
                    ErrorCode.CONFLICT,
                    "The IDE could not revert ${files.pathOf(target)} to '${label.name}': ${e.message}",
                    "Check the files in the IDE (Edit | Undo reverts what was written), then retry.",
                )
            }
        }

    private fun versions() = FileVersions(project, files, history, labels::live)

    override suspend fun isDirectory(path: String): ToolResult<Boolean> =
        withTarget(path) { ToolResult.success(it.isDirectory) }

    private suspend fun <T> withTarget(path: String, body: suspend (VirtualFile) -> ToolResult<T>): ToolResult<T> {
        if (!history.isEnabled) return disabled()
        val found = readAction {
            val file = files.find(path) ?: return@readAction ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "No file or directory at $path.",
                "For a deleted file, compare or revert its directory.",
            )
            if (files.originOf(file) != FileOrigin.PROJECT) {
                ToolResult.failure(
                    ErrorCode.INVALID_ARGUMENT,
                    "$path is not in the project's content; Local History covers project files only.",
                )
            } else {
                ToolResult.success(file)
            }
        }
        return when (found) {
            is ToolResult.Success -> body(found.value)
            is ToolResult.Failure -> found
        }
    }

    private fun disabled(): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.UNSUPPORTED,
        "Local History is disabled in this IDE.",
        "Use mc_vcs_history for committed versions.",
    )
}
