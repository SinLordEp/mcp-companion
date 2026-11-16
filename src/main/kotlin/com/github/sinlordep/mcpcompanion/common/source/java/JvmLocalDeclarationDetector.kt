package com.github.sinlordep.mcpcompanion.common.source.java

import com.github.sinlordep.mcpcompanion.common.source.psi.LocalDeclarationDetector
import com.intellij.psi.PsiElement
import org.jetbrains.uast.UDeclaration
import org.jetbrains.uast.ULocalVariable
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.toUElement

/**
 * Parameters and local variables of JVM languages (Java, Kotlin, ...) through UAST. Registered in
 * `mcp-companion-java.xml`.
 */
class JvmLocalDeclarationDetector : LocalDeclarationDetector {

    override fun isLocal(declaration: PsiElement): Boolean? = when (declaration.toUElement()) {
        is UParameter, is ULocalVariable -> true
        is UDeclaration -> false
        else -> null
    }
}
