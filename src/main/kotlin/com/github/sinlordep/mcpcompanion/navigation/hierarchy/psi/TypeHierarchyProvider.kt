package com.github.sinlordep.mcpcompanion.navigation.hierarchy.psi

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope

/**
 * Direct type relations of one language family. Registered from the optional descriptor of the language plugin it
 * needs (for JVM languages: `META-INF/mcp-companion-java.xml`). All functions are called inside a read action.
 */
interface TypeHierarchyProvider {

    /** The type [element] declares, in the form the other functions accept; null when not handled here. */
    fun typeOf(element: PsiElement): PsiElement?

    /** Direct supertypes of [type] (a result of [typeOf]): superclass and implemented or extended interfaces. */
    fun directSupertypes(type: PsiElement): List<PsiElement>

    /** Feeds the direct subtypes of [type] in [scope] to [consumer] until it returns false. */
    fun processDirectSubtypes(type: PsiElement, scope: GlobalSearchScope, consumer: (PsiElement) -> Boolean)

    companion object {
        val EP: ExtensionPointName<TypeHierarchyProvider> =
            ExtensionPointName("com.github.sinlordep.mcpcompanion.typeHierarchy")
    }
}
