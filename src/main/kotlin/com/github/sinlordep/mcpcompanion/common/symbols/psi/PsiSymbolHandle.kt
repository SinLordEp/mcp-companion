package com.github.sinlordep.mcpcompanion.common.symbols.psi

import com.github.sinlordep.mcpcompanion.common.symbols.SymbolHandle
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer

/** [SymbolHandle] backed by a smart pointer, so it survives PSI reparses. Call [element] inside a read action. */
class PsiSymbolHandle(private val pointer: SmartPsiElementPointer<PsiElement>) : SymbolHandle {
    fun element(): PsiElement? = pointer.element
}

/**
 * The PSI element behind this handle, or null when it no longer exists (or the handle is not one of ours). Call inside
 * a read action.
 */
fun SymbolHandle.psiElement(): PsiElement? = (this as? PsiSymbolHandle)?.element()?.takeIf { it.isValid }
