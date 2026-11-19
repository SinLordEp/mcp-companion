package com.github.sinlordep.mcpcompanion.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Layers of the refactoring tools (docs/notes-step8.md, docs/notes-step12.md): use cases at the feature root on the
 * shared `common.refactoring` actions, a language-neutral gateway in `psi`, the language supports in `java` (one
 * segment per optional language plugin).
 */
class RefactoringLayersTest {

    private val root = "com.github.sinlordep.mcpcompanion"

    private val refactoringFeatures = listOf("move", "changesignature", "pullup", "extractmethod")

    private val languageSegments = listOf("java")

    /**
     * The Java plugin's refactoring and PSI APIs that the language supports use; with the general list of
     * `LayeringTest`, only `java` packages may use them.
     */
    private val javaRefactoringApis = listOf(
        "com.intellij.lang.java",
        "com.intellij.psi.JavaPsiFacade",
        "com.intellij.psi.PsiClass",
        "com.intellij.psi.PsiMethod",
        "com.intellij.psi.PsiNameHelper",
        "com.intellij.psi.PsiMember",
        "com.intellij.psi.PsiModifier",
        "com.intellij.psi.PsiJavaFile",
        "com.intellij.refactoring.JavaRefactoringFactory",
        "com.intellij.refactoring.JavaRefactoringSettings",
        "com.intellij.refactoring.move.moveClassesOrPackages",
        "com.intellij.refactoring.changeSignature.ChangeSignatureProcessor",
        "com.intellij.refactoring.changeSignature.ParameterInfoImpl",
        "com.intellij.refactoring.memberPullUp",
        "com.intellij.refactoring.util.classMembers",
        "com.intellij.refactoring.extractMethod",
        "com.intellij.refactoring.util.ConflictsUtil",
        "com.intellij.util.VisibilityUtil",
    )

    private val production = Konsist.scopeFromProduction()

    private fun filesIn(subPackage: String): List<KoFileDeclaration> {
        val packageName = "$root.$subPackage"
        return production.files.filter { file ->
            val name = file.packagee?.name.orEmpty()
            name == packageName || name.startsWith("$packageName.")
        }
    }

    private fun KoFileDeclaration.importsAny(prefixes: List<String>): Boolean =
        imports.any { import -> prefixes.any { import.name == it || import.name.startsWith("$it.") } }

    @Test
    fun `refactoring use cases know neither their gateways nor the language supports`() {
        for (feature in refactoringFeatures) {
            val useCases = filesIn(feature).filterNot { file ->
                file.packagee?.name.orEmpty().split('.').any { it == "psi" || it in languageSegments }
            }
            Assertions.assertTrue(useCases.isNotEmpty(), "no $feature use-case files found")
            useCases.assertFalse {
                it.importsAny(listOf("$root.$feature.psi") + languageSegments.map { "$root.$feature.$it" })
            }
        }
    }

    @Test
    fun `the language-neutral refactoring gateways do not use the language supports`() {
        for (feature in refactoringFeatures) {
            val neutral = filesIn("$feature.psi")
            Assertions.assertTrue(neutral.isNotEmpty(), "no $feature.psi files found")
            neutral.assertFalse { it.importsAny(languageSegments.map { segment -> "$root.$feature.$segment" }) }
            neutral.assertFalse { it.importsAny(javaRefactoringApis) }
        }
    }

    @Test
    fun `Java refactoring APIs are used only in java packages`() {
        production.files.filterNot { file -> file.packagee?.name.orEmpty().split('.').contains("java") }
            .assertFalse { it.importsAny(javaRefactoringApis) }
    }

    @Test
    fun `the shared refactoring runner is language-neutral`() {
        val shared = filesIn("common.refactoring").filterNot { it.packagee?.name == "$root.common.refactoring.java" }
        Assertions.assertTrue(shared.isNotEmpty(), "no common.refactoring files found")
        shared.assertFalse { it.importsAny(javaRefactoringApis) }
    }
}
