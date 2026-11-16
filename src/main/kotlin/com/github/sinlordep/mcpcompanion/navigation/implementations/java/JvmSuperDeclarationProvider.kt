package com.github.sinlordep.mcpcompanion.navigation.implementations.java

import com.github.sinlordep.mcpcompanion.common.symbols.java.JvmDeclarations
import com.github.sinlordep.mcpcompanion.navigation.implementations.psi.SuperDeclarationProvider
import com.intellij.psi.PsiElement

/**
 * Super declarations of JVM classes and methods (Java directly, Kotlin and others through UAST). Registered in
 * `META-INF/mcp-companion-java.xml`. Results are breadth-first, so the nearest come first.
 */
class JvmSuperDeclarationProvider : SuperDeclarationProvider {

    override fun superDeclarations(element: PsiElement): List<PsiElement>? =
        JvmDeclarations.classOf(element)?.let { psiClass -> closure(psiClass) { it.supers.asList() } }
            ?: JvmDeclarations.methodOf(element)?.let { method -> closure(method) { it.findSuperMethods().asList() } }

    /** Breadth-first transitive closure of [next], without [start] and without duplicates. */
    private fun <T : PsiElement> closure(start: T, next: (T) -> List<T>): List<PsiElement> {
        val seen = LinkedHashSet<T>()
        val queue = ArrayDeque(next(start))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current != start && seen.add(current)) queue.addAll(next(current))
        }
        return seen.toList()
    }
}
