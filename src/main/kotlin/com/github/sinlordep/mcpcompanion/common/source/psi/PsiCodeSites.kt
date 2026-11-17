package com.github.sinlordep.mcpcompanion.common.source.psi

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.source.CodeSite
import com.github.sinlordep.mcpcompanion.common.source.LineExcerpt
import com.github.sinlordep.mcpcompanion.common.source.SourceKey
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocation
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.StubBasedPsiElement
import com.intellij.psi.util.parents

/**
 * Describes places in PSI as platform-free [CodeSite]s, for any language. Call inside a read action.
 *
 * [keyOf] is cheap (no document, no tree walk); [ofReference] and [at] do the expensive part and are meant for the
 * hits of one page only. Open so that tests can count how often a site is described.
 *
 * The enclosing declaration is the chain of named, stub-based ancestors (classes, functions, fields and the like),
 * leaving out those that a [LocalDeclarationDetector] calls local (parameters, local variables), e.g. `Main.total`.
 * In a language without a detector, parameters and stub-based locals stay in the chain.
 */
open class PsiCodeSites(
    private val project: Project,
    private val files: ProjectFiles,
    private val detectors: () -> List<LocalDeclarationDetector> = { LocalDeclarationDetector.EP.extensionList },
) {

    /** Sort key of the place [reference] points from, or null for a reference outside any file. */
    fun keyOf(reference: PsiReference): SourceKey? {
        val element = reference.element
        val file = element.containingFile?.virtualFile ?: return null
        return SourceKey(file.url, element.textRange.startOffset + reference.rangeInElement.startOffset)
    }

    /** Where [reference] points from: the start of its range in the referring element. */
    open fun ofReference(reference: PsiReference): CodeSite? {
        val element = reference.element
        return at(element, element.textRange.startOffset + reference.rangeInElement.startOffset)
    }

    /** The place at [offset] of [element]'s file; [element] decides the enclosing declarations. */
    open fun at(element: PsiElement, offset: Int): CodeSite? {
        val file: PsiFile = element.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        if (offset !in 0..document.textLength) return null
        val line = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(line)
        val column = offset - lineStart + 1
        val lineText = document.getText(TextRange(lineStart, document.getLineEndOffset(line)))
        return CodeSite(
            location = SymbolLocation(files.pathOf(virtualFile), line + 1, column, files.originOf(virtualFile)),
            lineText = LineExcerpt.of(lineText, column),
            enclosingDeclaration = enclosingDeclarationOf(element),
        )
    }

    private fun enclosingDeclarationOf(element: PsiElement): String? {
        val localDetectors = detectors()
        return element.parents(withSelf = false)
            .filter { it is StubBasedPsiElement<*> && it is PsiNameIdentifierOwner }
            .filterNot { declaration -> localDetectors.any { it.isLocal(declaration) == true } }
            .mapNotNull { (it as PsiNameIdentifierOwner).name }
            .toList()
            .asReversed()
            .joinToString(".")
            .ifEmpty { null }
    }
}
