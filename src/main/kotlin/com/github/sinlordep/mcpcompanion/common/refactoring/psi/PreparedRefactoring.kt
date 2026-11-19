package com.github.sinlordep.mcpcompanion.common.refactoring.psi

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap

/**
 * A refactoring of the IDE, set up by a language's support and analysed, ready for [PsiRefactoringRunner]: what it
 * would rewrite and what conflicts with it, found without asking the user anything.
 *
 * @param usages the references the refactoring rewrites, as the IDE's processor found them; none for a refactoring
 *   without references to rewrite (e.g. extracting a method).
 * @param conflicts what the IDE's conflict analysis reports: an element (the place) and messages, possibly HTML.
 * @param declarationFiles files the refactoring changes besides those of [usages], e.g. the file of the declaration.
 * @param commandName the name of the undoable command in Edit | Undo, user interface text.
 * @param summary what the refactoring creates, in one line, when the request does not say it all (e.g. the
 *   signature of an extracted method); see `RefactoringAnalysis.summary`.
 * @param perform carries the refactoring out with [usages]. Called on the EDT outside any write action, inside the
 *   command; it runs the IDE's own refactoring execution (with its write action, or in one it takes itself) and must
 *   not ask anything.
 */
class PreparedRefactoring(
    val usages: Array<UsageInfo>,
    val conflicts: MultiMap<PsiElement, String>,
    val declarationFiles: List<VirtualFile>,
    val commandName: String,
    val summary: String? = null,
    val perform: (Array<UsageInfo>) -> Unit,
)
