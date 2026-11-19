package com.github.sinlordep.mcpcompanion.quickfix.psi

import com.github.sinlordep.mcpcompanion.McpCompanionBundle
import com.github.sinlordep.mcpcompanion.common.build.ProblemSeverity
import com.github.sinlordep.mcpcompanion.common.edits.psi.UndoableCommand
import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.source.LineExcerpt
import com.github.sinlordep.mcpcompanion.common.symbols.FileOrigin
import com.github.sinlordep.mcpcompanion.quickfix.AnalysedFile
import com.github.sinlordep.mcpcompanion.quickfix.AnalysedFix
import com.github.sinlordep.mcpcompanion.quickfix.AnalysedProblem
import com.github.sinlordep.mcpcompanion.quickfix.FixAttempt
import com.github.sinlordep.mcpcompanion.quickfix.ProblemHandle
import com.github.sinlordep.mcpcompanion.quickfix.QuickFixGateway
import com.intellij.codeInsight.daemon.ProblemHighlightFilter
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandExecutor
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.CRC32

/**
 * Quick fixes without an editor, a dialog or a change of focus. The problems and their registered fixes come from the
 * editor's highlighting, run without an editor ([FileHighlighting], [FixCandidates], [FixDescriptions]). Only fixes in
 * the IDE's `ModCommand` form are applied: such a fix first computes its change as data (on a copy, changing nothing),
 * which shows whether it needs the user ([ModCommandChecks]); the change is then carried out by the IDE's batch
 * execution (`ModCommandExecutor.executeInBatch`, the path of Code Cleanup and "fix all"), inside one undoable command.
 * Fixes in the older form (`IntentionAction.invoke` with an editor) may open dialogs, popups or templates that cannot
 * be known beforehand: they are listed with a refusal and never run. See docs/notes-step9.md.
 */
internal class PsiQuickFixGateway(
    private val project: Project,
    private val files: ProjectFiles,
    private val highlighting: FileHighlighting,
    private val candidates: FixCandidates,
    private val descriptions: FixDescriptions,
    private val checks: ModCommandChecks,
) : QuickFixGateway {

    private class PsiProblem(val info: HighlightInfo, val file: VirtualFile, val stamp: Long) : ProblemHandle

    override suspend fun analyse(path: String): ToolResult<AnalysedFile> {
        val file = when (val found = readAction { fileToFix(path) }) {
            is ToolResult.Success -> found.value
            is ToolResult.Failure -> return found
        }
        val infos = highlighting.highlights(file)
        return readAction {
            val document = documentOf(file)
                ?: return@readAction ToolResult.failure(ErrorCode.UNSUPPORTED, "$path is not a text file.")
            val stamp = document.modificationStamp
            val problems = infos.asSequence()
                .filter { !it.description.isNullOrBlank() && it.severity >= HighlightSeverity.WEAK_WARNING }
                .filter { it.startOffset <= it.endOffset && it.endOffset <= document.textLength }
                .distinctBy { Triple(it.startOffset, it.endOffset, it.description) }
                .sortedWith(compareBy({ it.startOffset }, { it.endOffset }, { it.description }))
                .map { problemOf(it, document, file, stamp) }
                .toList()
            ToolResult.success(AnalysedFile(files.pathOf(file), contentStamp(document), problems))
        }
    }

    override suspend fun fixes(problem: AnalysedProblem): ToolResult<List<AnalysedFix>> {
        val handle = problem.handle as PsiProblem
        return smartReadAction(project) {
            val psiFile = PsiManager.getInstance(project).findFile(handle.file)
            // Changed since the analysis: the problem's fixes are gone with it.
            if (psiFile == null || documentOf(handle.file)?.modificationStamp != handle.stamp) {
                return@smartReadAction ToolResult.success(emptyList())
            }
            val described = ArrayList<List<AnalysedFix>>()
            for (candidate in candidates.of(handle.info, psiFile)) {
                val fixes = descriptions.describe(candidate, psiFile, handle.stamp, (described.size + 1).toString())
                if (fixes.isNotEmpty()) described += fixes
            }
            ToolResult.success(described.flatten())
        }
    }

    override suspend fun apply(fix: AnalysedFix): ToolResult<FixAttempt> {
        val handle = fix.handle as PsiFixHandle
        val action = handle.action ?: return ToolResult.failure(ErrorCode.UNSUPPORTED, fix.refusal.orEmpty())
        // Computed again for the current content: the change that is applied is the one checked here.
        val prepared = smartReadAction(project) {
            val psiFile = PsiManager.getInstance(project).findFile(handle.file)
            if (psiFile == null ||
                documentOf(handle.file)?.modificationStamp != handle.stamp
            ) {
                return@smartReadAction null
            }
            val context = descriptions.contextAt(psiFile, handle.offset)
            val command = action.perform(context)
            Triple(context, command, checks.refusal(command))
        } ?: return ToolResult.success(FixAttempt.Stale)
        val (context, command, refusal) = prepared
        if (refusal != null) {
            return ToolResult.failure(
                ErrorCode.UNSUPPORTED,
                "The fix '${fix.name}' cannot be applied without the user now: $refusal. Nothing was changed.",
                "Call action=list again.",
            )
        }
        return withContext(Dispatchers.EDT) { execute(fix.name, handle, context, command) }
    }

    private fun execute(
        name: String,
        fix: PsiFixHandle,
        context: ActionContext,
        command: ModCommand,
    ): ToolResult<FixAttempt> {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        if (documentOf(fix.file)?.modificationStamp != fix.stamp) return ToolResult.success(FixAttempt.Stale)
        var result: ModCommandExecutor.BatchExecutionResult = ModCommandExecutor.Result.NOTHING
        val recorded = UndoableCommand(project).run(McpCompanionBundle.message("command.quick.fix", name)) {
            result = ModCommandExecutor.getInstance().executeInBatch(context, command)
        }
        return when (result) {
            ModCommandExecutor.Result.SUCCESS -> {
                val changed = (recorded + checks.changedFiles(command)).filter { it.isValid && !it.isDirectory }
                ToolResult.success(FixAttempt.Applied(changed.map(files::pathOf).distinct().sorted()))
            }

            // The batch execution checks that each file still has the text the change was computed for.
            ModCommandExecutor.Result.ABORT -> ToolResult.success(FixAttempt.Stale)

            else -> ToolResult.failure(
                ErrorCode.CONFLICT,
                "The IDE did not apply '$name': ${result.message ?: result.toString().lowercase()}.",
                "Undo the last command in the IDE if anything changed, then call action=list again.",
            )
        }
    }

    private fun fileToFix(path: String): ToolResult<VirtualFile> {
        val file = files.find(path)?.takeUnless { it.isDirectory } ?: return ToolResult.failure(
            ErrorCode.NOT_FOUND,
            "File not found: $path",
            "Pass a path relative to the project root (.. allowed) or an absolute path.",
        )
        val psiFile = PsiManager.getInstance(project).findFile(file)
        return when {
            files.originOf(file) != FileOrigin.PROJECT || !file.isWritable -> ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "$path is not a writable file of the project (it is in a library, the SDK, outside the project or " +
                    "read-only); quick fixes change only project files.",
                "Pass a file of the project's own sources.",
            )

            psiFile == null -> ToolResult.failure(ErrorCode.UNSUPPORTED, "$path is not a file the IDE can parse.")

            !ProblemHighlightFilter.shouldProcessFileInBatch(psiFile) -> ToolResult.failure(
                ErrorCode.UNSUPPORTED,
                "The IDE does not analyse $path without an editor (e.g. it is outside the source roots).",
                "Use a file under a source or test root.",
            )

            else -> ToolResult.success(file)
        }
    }

    private fun problemOf(info: HighlightInfo, document: Document, file: VirtualFile, stamp: Long): AnalysedProblem {
        val startLine = document.getLineNumber(info.startOffset)
        val endLine = document.getLineNumber(info.endOffset)
        val lineStart = document.getLineStartOffset(startLine)
        val column = info.startOffset - lineStart + 1
        val lineText = document.getText(TextRange(lineStart, document.getLineEndOffset(startLine)))
        return AnalysedProblem(
            severity = if (info.severity >= HighlightSeverity.ERROR) ProblemSeverity.ERROR else ProblemSeverity.WARNING,
            message = info.description.orEmpty(),
            startOffset = info.startOffset,
            endOffset = info.endOffset,
            line = startLine + 1,
            column = column,
            endLine = endLine + 1,
            endColumn = info.endOffset - document.getLineStartOffset(endLine) + 1,
            lineText = LineExcerpt.of(lineText, column),
            handle = PsiProblem(info, file, stamp),
        )
    }

    private fun documentOf(file: VirtualFile): Document? = FileDocumentManager.getInstance().getDocument(file)

    /** Identifies the text: any edit gives another stamp. */
    private fun contentStamp(document: Document): String {
        val checksum = CRC32()
        checksum.update(document.text.toByteArray())
        return "${document.textLength}-${"%08x".format(checksum.value)}"
    }
}
