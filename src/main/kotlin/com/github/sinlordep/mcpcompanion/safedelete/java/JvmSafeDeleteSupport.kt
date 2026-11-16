package com.github.sinlordep.mcpcompanion.safedelete.java

import com.github.sinlordep.mcpcompanion.common.symbols.java.JvmDeclarations
import com.github.sinlordep.mcpcompanion.safedelete.psi.SafeDeleteSupport
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.toUElementOfType

/**
 * Safe-delete rules for JVM languages (Java directly, Kotlin and others through UAST). Registered in
 * `mcp-companion-java.xml`.
 */
class JvmSafeDeleteSupport : SafeDeleteSupport {

    override fun refusal(element: PsiElement): String? = if (element.toUElementOfType<UParameter>() != null) {
        "Deleting a parameter changes the signature of its function and every call site; the IDE does that only " +
            "through an interactive refactoring."
    } else {
        null
    }

    override fun overridden(element: PsiElement): List<PsiElement> =
        JvmDeclarations.methodOf(element)?.findSuperMethods()?.toList().orEmpty()

    override fun overrides(declaration: PsiElement, target: PsiElement): Boolean {
        val method = JvmDeclarations.methodOf(declaration) ?: return false
        val targetSource = JvmDeclarations.methodOf(target)?.navigationElement ?: return false
        val seen = HashSet<PsiMethod>()
        val queue = ArrayDeque(method.findSuperMethods().asList())
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current.navigationElement == targetSource) return true
            if (seen.add(current)) queue.addAll(current.findSuperMethods())
        }
        return false
    }
}
