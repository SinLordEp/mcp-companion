package com.github.sinlordep.mcpcompanion.navigation.hierarchy.java

import com.github.sinlordep.mcpcompanion.common.symbols.java.JvmDeclarations
import com.github.sinlordep.mcpcompanion.navigation.hierarchy.psi.TypeHierarchyProvider
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DirectClassInheritorsSearch

/**
 * Type relations of JVM classes: Java directly, Kotlin and other JVM languages through their light classes (UAST).
 * Registered in `META-INF/mcp-companion-java.xml`. Subtypes include anonymous classes.
 */
class JvmTypeHierarchyProvider : TypeHierarchyProvider {

    override fun typeOf(element: PsiElement): PsiElement? = JvmDeclarations.classOf(element)

    override fun directSupertypes(type: PsiElement): List<PsiElement> = (type as PsiClass).supers.asList()

    override fun processDirectSubtypes(type: PsiElement, scope: GlobalSearchScope, consumer: (PsiElement) -> Boolean) {
        DirectClassInheritorsSearch.search(type as PsiClass, scope).forEach { subtype: PsiClass -> consumer(subtype) }
    }
}
