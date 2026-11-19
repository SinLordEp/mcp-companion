package com.github.sinlordep.mcpcompanion.run.java

import com.github.sinlordep.mcpcompanion.run.ide.CodeRunGuard
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier

/**
 * Tests declared in an abstract class: the IDE's JUnit, TestNG and Gradle producers then ask in `onFirstRun` which
 * subclass (or all of them) to run, in a popup; without that step the configuration would run the abstract class
 * itself. Registered in `mcp-companion-java.xml`. Covers Java declarations; Kotlin test classes are not checked
 * (their producers hand over Kotlin PSI).
 */
class JvmAbstractTestGuard : CodeRunGuard {

    override fun refusal(source: PsiElement): String? {
        val owner = when (source) {
            is PsiMethod -> source.containingClass
            is PsiClass -> source
            else -> null
        } ?: return null
        if (owner.isInterface || !owner.hasModifierProperty(PsiModifier.ABSTRACT)) return null
        return "its tests are declared in the abstract class ${owner.qualifiedName ?: owner.name}, and the IDE asks " +
            "which subclass to run; pass a concrete subclass (qualifiedName=<subclass> or <subclass>#<method>)"
    }
}
