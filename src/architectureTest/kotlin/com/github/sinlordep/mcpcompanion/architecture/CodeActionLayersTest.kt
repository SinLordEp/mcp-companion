package com.github.sinlordep.mcpcompanion.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Layers of `mc_quick_fix` and of `mc_run start_at` (docs/notes-step9.md): use cases at the feature root, a
 * language-neutral gateway (`quickfix.psi`, `run.ide`), the language supports in `java`. The platform's highlighting
 * internals that sit in `impl` packages without being marked internal are owned by one gateway each, and code edits
 * run through the shared undoable command.
 */
class CodeActionLayersTest {

    private val root = "com.github.sinlordep.mcpcompanion"

    /** Highlighting results with their fixes; public, not annotated, but in the platform's `impl` package. */
    private val highlightingInternals = listOf(
        "com.intellij.codeInsight.daemon.impl.MainPassesRunner",
        "com.intellij.codeInsight.daemon.impl.HighlightInfo",
    )

    /** The IDE's run configurations made from code; only the `start_at` gateway uses them. */
    private val codeRunApis = listOf("com.intellij.execution.actions.ConfigurationContext")

    /**
     * Java plugin APIs of the Java supports of these tools (in addition to those of `LayeringTest`); outside a `java`
     * package they would break the plugin in IDEs without Java.
     */
    private val javaPluginApis = listOf(
        "com.intellij.psi.PsiJavaCodeReferenceElement",
        "com.intellij.psi.PsiMethodCallExpression",
        "com.intellij.psi.PsiModifier",
        "com.intellij.codeInsight.daemon.impl.quickfix",
    )

    /** Fixes as data and their batch execution. */
    private val modCommandApis = listOf("com.intellij.modcommand")

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
    fun `quick fix use cases know neither their gateway nor the language supports`() {
        val useCases = filesIn("quickfix").filterNot { file ->
            file.packagee?.name.orEmpty().split('.').any { it == "psi" || it == "java" }
        }
        Assertions.assertTrue(useCases.isNotEmpty(), "no quickfix use-case files found")
        useCases.assertFalse { it.importsAny(listOf("$root.quickfix.psi", "$root.quickfix.java", "com.intellij")) }
    }

    @Test
    fun `the language-neutral quick fix gateway does not use the language supports`() {
        val neutral = filesIn("quickfix.psi")
        Assertions.assertTrue(neutral.isNotEmpty(), "no quickfix.psi files found")
        neutral.assertFalse { it.importsAny(listOf("$root.quickfix.java")) }
    }

    @Test
    fun `the Java plugin APIs of the Java supports are used only in java packages`() {
        production.files.filterNot { file -> file.packagee?.name.orEmpty().split('.').contains("java") }
            .assertFalse { it.importsAny(javaPluginApis) }
    }

    @Test
    fun `the highlighting internals are used only by the quick fix gateway`() {
        val owner = filesIn("quickfix.psi").toSet()
        (production.files - owner).assertFalse { it.importsAny(highlightingInternals) }
    }

    @Test
    fun `fixes are carried out only by the quick fix gateway`() {
        val owner = (filesIn("quickfix.psi") + filesIn("quickfix.java")).toSet()
        (production.files - owner).assertFalse { it.importsAny(modCommandApis) }
    }

    @Test
    fun `run configurations from code are made only by the start_at gateway`() {
        val owner = filesIn("run.ide").filter { it.name == "IdeCodeRunGateway" }
        Assertions.assertEquals(1, owner.size, "IdeCodeRunGateway not found")
        (production.files - owner.toSet()).assertFalse { it.importsAny(codeRunApis) }
    }

    @Test
    fun `code changes run as the shared undoable command, not as commands of their own`() {
        val shared = filesIn("common.edits").toSet()
        (production.files - shared)
            .filter { file ->
                file.packagee?.name.orEmpty().let {
                    it.contains(".quickfix") ||
                        it.contains(".common.refactoring")
                }
            }
            .assertFalse { it.importsAny(listOf("com.intellij.openapi.command.CommandProcessor")) }
    }
}
