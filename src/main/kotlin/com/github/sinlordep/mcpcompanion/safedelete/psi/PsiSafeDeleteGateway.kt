package com.github.sinlordep.mcpcompanion.safedelete.psi

import com.github.sinlordep.mcpcompanion.McpCompanionBundle
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolFailures
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolHandle
import com.github.sinlordep.mcpcompanion.common.symbols.psi.psiElement
import com.github.sinlordep.mcpcompanion.safedelete.DeletionAttempt
import com.github.sinlordep.mcpcompanion.safedelete.DeletionPlan
import com.github.sinlordep.mcpcompanion.safedelete.SafeDeleteAnalysis
import com.github.sinlordep.mcpcompanion.safedelete.SafeDeleteGateway
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor
import com.intellij.refactoring.safeDelete.SafeDeleteProcessorDelegate
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteCustomUsageInfo
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewUtil
import com.intellij.util.IncorrectOperationException
import com.intellij.util.containers.MultiMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Safe delete with the platform's analysis and without any of its dialogs.
 *
 * The analysis asks the language's `SafeDeleteProcessorDelegate` for usages (`findUsages`) and conflicts
 * (`findConflicts`), the non-interactive parts of the IDE's Safe Delete. It never calls the delegate methods that
 * consult the user (`getElementsToSearch`, `getAdditionalElementsToDelete`, `preprocessUsages`) and never runs a
 * `BaseRefactoringProcessor` (usage preview, conflicts dialog, read-only prompt, modal progress, error dialog). The
 * deletion repeats only the effect of `SafeDeleteProcessor.performRefactoring`: perform the analysed usages, let the
 * delegate prepare, delete the element; all in one `WriteCommandAction` on the EDT, so it is one undoable step. See
 * `docs/notes-step2.md` for the full list of interactive code paths and why none is reached.
 */
class PsiSafeDeleteGateway(
    private val project: Project,
    private val classifier: SafeDeleteUsageClassifier,
    private val delegates: () -> List<SafeDeleteProcessorDelegate> = {
        SafeDeleteProcessorDelegate.EP_NAME.extensionList
    },
    private val supports: () -> List<SafeDeleteSupport> = { SafeDeleteSupport.EP.extensionList },
) : SafeDeleteGateway {

    private class PsiDeletionPlan(
        val target: SmartPsiElementPointer<PsiElement>,
        val name: String,
        val cleanups: List<UsageInfo>,
        val overriders: List<SmartPsiElementPointer<PsiElement>>,
        val modificationCount: Long,
    ) : DeletionPlan

    override suspend fun analyse(target: SymbolHandle): ToolResult<SafeDeleteAnalysis> = smartReadAction(project) {
        // Light elements (e.g. the Java view of a Kotlin class found by qualified name) are deleted as their source.
        val element = target.psiElement()?.navigationElement ?: return@smartReadAction SymbolFailures.gone()
        refusalFor(element)?.let { return@smartReadAction it }
        val support = supports().firstOrNull()
        support?.refusal(element)?.let {
            return@smartReadAction ToolResult.failure(ErrorCode.UNSUPPORTED, it, ASK_USER)
        }
        val delegate = delegates().firstOrNull { it.handlesElement(element) }
        val usages = findUsages(element, delegate) ?: return@smartReadAction unsupported()
        val conflicts = MultiMap<PsiElement, String>()
        if (delegate != null && delegate.javaClass.name in NON_INTERACTIVE_CONFLICT_FINDERS) {
            delegate.findConflicts(element, arrayOf(element), usages, conflicts)
        }
        val classified = classifier.classify(element, usages, conflicts, support)
        ToolResult.success(
            SafeDeleteAnalysis(
                blockers = classified.blockers,
                cleanups = classified.cleanups.size,
                alsoDeleted = classified.alsoDeleted,
                plan = PsiDeletionPlan(
                    SmartPointerManager.createPointer(element),
                    (element as? PsiNamedElement)?.name.orEmpty(),
                    classified.cleanups,
                    classified.overriders.map(SmartPointerManager::createPointer),
                    PsiModificationTracker.getInstance(project).modificationCount,
                ),
            ),
        )
    }

    // The coroutine helper `com.intellij.openapi.command.writeCommandAction` is @ApiStatus.Experimental in 263, so the
    // stable builder runs on the EDT instead.
    override suspend fun delete(plan: DeletionPlan): ToolResult<DeletionAttempt> = withContext(Dispatchers.EDT) {
        val psiPlan = plan as PsiDeletionPlan
        WriteCommandAction.writeCommandAction(project)
            .withName(McpCompanionBundle.message("command.safe.delete", psiPlan.name))
            .compute<ToolResult<DeletionAttempt>, RuntimeException> { deleteNow(psiPlan) }
    }

    /** Runs inside the write command: no other change can happen between the staleness check and the deletion. */
    private fun deleteNow(psiPlan: PsiDeletionPlan): ToolResult<DeletionAttempt> {
        val element = psiPlan.target.element
        if (element == null ||
            PsiModificationTracker.getInstance(project).modificationCount != psiPlan.modificationCount
        ) {
            return ToolResult.success(DeletionAttempt.Stale)
        }
        return try {
            psiPlan.cleanups.filterIsInstance<SafeDeleteCustomUsageInfo>().forEach { it.performRefactoring() }
            (psiPlan.overriders.mapNotNull { it.element } + element).forEach(::deleteWithDelegate)
            ToolResult.success(DeletionAttempt.Deleted)
        } catch (e: IncorrectOperationException) {
            ToolResult.failure(
                ErrorCode.CONFLICT,
                "The IDE could not complete the deletion: ${e.message}",
                "Undo the last command in the IDE (Safe Delete) to restore any partial change.",
            )
        }
    }

    /** As `SafeDeleteProcessor.performRefactoring` does for each deleted element: let the language prepare, delete. */
    private fun deleteWithDelegate(element: PsiElement) {
        if (!element.isValid) return
        delegates().filter { it.handlesElement(element) }.forEach { it.prepareForDeletion(element) }
        element.delete()
    }

    private fun findUsages(element: PsiElement, delegate: SafeDeleteProcessorDelegate?): Array<UsageInfo>? {
        val found = ArrayList<UsageInfo>()
        when {
            delegate != null -> delegate.findUsages(element, arrayOf(element), found)
            element is PsiNamedElement -> SafeDeleteProcessor.findGenericElementUsages(element, found, arrayOf(element))
            else -> return null
        }
        return UsageViewUtil.removeDuplicatedUsages(found.toTypedArray())
    }

    private fun refusalFor(element: PsiElement): ToolResult<Nothing>? {
        if (!SafeDeleteProcessor.validElement(element)) return unsupported()
        val file = element.containingFile?.virtualFile
        val inProjectSources = file != null && ProjectFileIndex.getInstance(project).isInContent(file)
        return if (inProjectSources && element.isWritable) {
            null
        } else {
            ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "Only declarations in writable project files can be deleted.",
                "Point at a declaration in the project's own sources, not in a library or a read-only file.",
            )
        }
    }

    private fun unsupported(): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.UNSUPPORTED,
        "The IDE cannot safely delete this kind of symbol.",
        "Point at a declaration such as a class, method, field or variable.",
    )

    private companion object {
        const val ASK_USER = "Edit the code yourself, or ask the user to run the IDE's refactoring."

        /**
         * Delegates whose `findConflicts` was checked to never consult the user (docs/notes-step2.md). Others are not
         * asked for conflicts: e.g. Kotlin's (K2) runs an analysis in a modal progress window on the EDT.
         */
        val NON_INTERACTIVE_CONFLICT_FINDERS = setOf("com.intellij.refactoring.safeDelete.JavaSafeDeleteProcessor")
    }
}
