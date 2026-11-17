package com.github.sinlordep.mcpcompanion.navigation.implementations.psi

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement

/**
 * Knows what declarations of one language family extend, implement or override. Registered from the optional
 * descriptor of the language plugin it needs (for JVM languages: `META-INF/mcp-companion-java.xml`).
 */
interface SuperDeclarationProvider {

    /**
     * Every supertype of a class, or every method a method overrides or implements, transitively, nearest first and
     * without duplicates; null when this provider does not handle [element]. Called inside a read action.
     */
    fun superDeclarations(element: PsiElement): List<PsiElement>?

    companion object {
        val EP: ExtensionPointName<SuperDeclarationProvider> =
            ExtensionPointName("com.github.sinlordep.mcpcompanion.superDeclarations")
    }
}
