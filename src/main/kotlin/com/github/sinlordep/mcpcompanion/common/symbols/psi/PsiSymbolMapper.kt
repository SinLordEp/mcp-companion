package com.github.sinlordep.mcpcompanion.common.symbols.psi

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolLocation
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiQualifiedNamedElement
import com.intellij.psi.SmartPointerManager

/** Describes a PSI element as a platform-free [LocatedSymbol]. Must be called inside a read action. */
class PsiSymbolMapper(private val project: Project, private val files: ProjectFiles) {

    /** The symbol for [element], or null when the element has no name. */
    fun map(element: PsiElement): LocatedSymbol? {
        val name = (element as? PsiNamedElement)?.name ?: return null
        return describe(element, name)
    }

    /**
     * Like [map], but an element without a name (an anonymous class, a lambda) is described as
     * [LocatedSymbol.ANONYMOUS] at its location.
     */
    fun mapOrAnonymous(element: PsiElement): LocatedSymbol =
        describe(element, (element as? PsiNamedElement)?.name ?: LocatedSymbol.ANONYMOUS)

    /** Where [element] is, or null for elements without a file or document (e.g. some synthetic elements). */
    fun locationOf(element: PsiElement): SymbolLocation? {
        val target = element.navigationElement
        val file = target.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        val offset = (target as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: target.textOffset
        val line = document.getLineNumber(offset)
        return SymbolLocation(
            path = files.pathOf(virtualFile),
            line = line + 1,
            column = offset - document.getLineStartOffset(line) + 1,
            origin = files.originOf(virtualFile),
        )
    }

    private fun describe(element: PsiElement, name: String): LocatedSymbol = LocatedSymbol(
        name = name,
        qualifiedName = (element as? PsiQualifiedNamedElement)?.qualifiedName,
        location = locationOf(element),
        handle = PsiSymbolHandle(SmartPointerManager.createPointer(element)),
    )
}
