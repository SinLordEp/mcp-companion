package com.github.sinlordep.mcpcompanion.common.symbols.psi

import com.github.sinlordep.mcpcompanion.common.paths.ProjectPathRenderer
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
class PsiSymbolMapper(private val project: Project, private val paths: ProjectPathRenderer) {

    /** The symbol for [element], or null when the element has no name. */
    fun map(element: PsiElement): LocatedSymbol? {
        val name = (element as? PsiNamedElement)?.name ?: return null
        return LocatedSymbol(
            name = name,
            qualifiedName = (element as? PsiQualifiedNamedElement)?.qualifiedName,
            location = locationOf(element.navigationElement),
            handle = PsiSymbolHandle(SmartPointerManager.createPointer(element)),
        )
    }

    private fun locationOf(element: PsiElement): SymbolLocation? {
        val file = element.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        val offset = (element as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: element.textOffset
        val line = document.getLineNumber(offset)
        return SymbolLocation(
            path = paths.render(virtualFile.path),
            line = line + 1,
            column = offset - document.getLineStartOffset(line) + 1,
        )
    }
}
