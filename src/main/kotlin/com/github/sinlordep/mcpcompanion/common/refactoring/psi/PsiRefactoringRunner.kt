package com.github.sinlordep.mcpcompanion.common.refactoring.psi

import com.github.sinlordep.mcpcompanion.common.edits.psi.UndoableCommand
import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.refactoring.AffectedFile
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringAnalysis
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringAttempt
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringConflict
import com.github.sinlordep.mcpcompanion.common.refactoring.RefactoringPlan
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.source.psi.PsiCodeSites
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolFailures
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolHandle
import com.github.sinlordep.mcpcompanion.common.symbols.psi.psiElement
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.IncorrectOperationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the IDE's refactorings without any of their dialogs, for every refactoring tool and language.
 *
 * The IDE's interactive entry point (`BaseRefactoringProcessor.run`) searches under a modal progress window and then
 * shows conflicts in a dialog, or, in tests, throws them; it never hands them to the caller. So the language support
 * finds the usages and asks the IDE's conflict analysis itself ([PreparedRefactoring]), in a read action in the
 * background that waits for indexing and is cancelled with the MCP call. Only a refactoring without conflicts is
 * carried out: on the EDT, through the processor's public execution (`Refactoring.doRefactoring`,
 * `BaseRefactoringProcessor.executeEx`), which is the IDE's own undoable command with its write action, local history
 * entry, refactoring listeners and helpers (e.g. import optimisation), and the same in tests and in the IDE. See
 * `docs/notes-step8.md`.
 */
class PsiRefactoringRunner(
    private val project: Project,
    private val files: ProjectFiles,
    private val sites: PsiCodeSites,
) {

    private class PsiRefactoringPlan(val prepared: PreparedRefactoring, val modificationCount: Long) : RefactoringPlan

    /**
     * The element behind [handle] as a declaration in a writable project file (light elements, e.g. the Java view of
     * a Kotlin class, become their source), or the failure to report. Call inside a read action.
     */
    fun writableDeclaration(handle: SymbolHandle): ToolResult<PsiElement> {
        val element = handle.psiElement()?.navigationElement ?: return SymbolFailures.gone()
        val file = element.containingFile?.virtualFile
        val inProject = file != null && ProjectFileIndex.getInstance(project).isInContent(file)
        return if (inProject && element.isWritable) {
            ToolResult.success(element)
        } else {
            ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "Only declarations in writable project files can be refactored; this one is in a library, the SDK " +
                    "or a read-only file.",
                "Point at a declaration in the project's own sources.",
            )
        }
    }

    /**
     * Sets the refactoring up with [prepare] inside a smart read action (it waits for indexing and is restarted when
     * a write action interrupts it) and describes what it would do.
     */
    suspend fun analyse(prepare: () -> ToolResult<PreparedRefactoring>): ToolResult<RefactoringAnalysis> =
        smartReadAction(project) {
            prepare().map { prepared ->
                val affected = affectedFiles(prepared)
                RefactoringAnalysis(
                    files = affected.map { (file, references) ->
                        AffectedFile(files.pathOf(file), files.originOf(file), references)
                    },
                    referenceCount = prepared.usages.size,
                    conflicts = conflictsOf(prepared) + readOnly(affected.keys),
                    plan = PsiRefactoringPlan(prepared, PsiModificationTracker.getInstance(project).modificationCount),
                )
            }
        }

    /** Carries out [plan] on the EDT, unless the code changed since the analysis; see the class comment. */
    suspend fun apply(plan: RefactoringPlan): ToolResult<RefactoringAttempt> = withContext(Dispatchers.EDT) {
        val psiPlan = plan as PsiRefactoringPlan
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val prepared = psiPlan.prepared
        // Any PSI change since the analysis may have moved or removed usages: analyse again.
        val stale = PsiModificationTracker.getInstance(project).modificationCount != psiPlan.modificationCount ||
            prepared.declarationFiles.any { !it.isValid }
        if (stale) return@withContext ToolResult.success(RefactoringAttempt.Stale)
        try {
            // One command of our own, marked global: one Edit | Undo reverts the whole refactoring from anywhere;
            // the processor's own command nests into it.
            val changed = UndoableCommand(project).run(prepared.commandName) { prepared.perform(prepared.usages) }
            ToolResult.success(RefactoringAttempt.Applied(changed.map(files::pathOf).distinct().sorted()))
        } catch (e: IncorrectOperationException) {
            ToolResult.failure(
                ErrorCode.CONFLICT,
                "The IDE could not complete the refactoring: ${e.message}",
                "Undo the last command in the IDE to restore any partial change.",
            )
        }
    }

    /** Files with the number of usages in each; declaration files without usages count 0. */
    private fun affectedFiles(prepared: PreparedRefactoring): Map<VirtualFile, Int> {
        val counts = LinkedHashMap<VirtualFile, Int>()
        prepared.declarationFiles.forEach { counts[it] = 0 }
        prepared.usages.mapNotNull { it.virtualFile }.forEach { counts.merge(it, 1, Int::plus) }
        return counts
    }

    private fun conflictsOf(prepared: PreparedRefactoring): List<RefactoringConflict> =
        prepared.conflicts.entrySet().flatMap { (element, messages) ->
            val site = element?.takeIf { it.isValid }?.let { sites.at(it, it.textOffset) }
            messages.map { RefactoringConflict(StringUtil.removeHtmlTags(it, true).trim(), site) }
        }.distinct()

    private fun readOnly(affected: Collection<VirtualFile>): List<RefactoringConflict> =
        affected.filterNot { it.isWritable }.map { file ->
            RefactoringConflict("${files.pathOf(file)} is read-only; the refactoring would have to change it.", null)
        }
}
