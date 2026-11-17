package com.github.sinlordep.mcpcompanion.common.symbols.java

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.toUElementOfType

/**
 * Views a declaration of any JVM language as Java PSI: Java elements as they are, others (Kotlin, Groovy, ...)
 * through UAST, whose `javaPsi` is the language's light class or method. Needs the Java plugin; call inside a read
 * action.
 */
object JvmDeclarations {

    fun classOf(element: PsiElement): PsiClass? = element as? PsiClass ?: element.toUElementOfType<UClass>()?.javaPsi

    fun methodOf(element: PsiElement): PsiMethod? =
        element as? PsiMethod ?: element.toUElementOfType<UMethod>()?.javaPsi
}
