package com.github.sinlordep.mcpcompanion.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Layers of `mc_file_structure`, `mc_local_history` and `mc_vcs_history` (docs/notes-step10.md): use cases at the
 * feature roots, one gateway package each (`structure.psi` with the JVM facts in `structure.java`, `localhistory.ide`,
 * `vcshistory.vcs` for the optional VCS support), and the shared diff rendering in `common.diff`.
 */
class StructureAndHistoryLayersTest {

    private val root = "com.github.sinlordep.mcpcompanion"

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

    private fun assertOnlyIn(owners: List<KoFileDeclaration>, apis: List<String>) {
        Assertions.assertTrue(owners.isNotEmpty(), "no owner files of $apis found")
        (production.files - owners.toSet()).assertFalse { it.importsAny(apis) }
    }

    @Test
    fun `use cases know no gateway package of their feature`() {
        for ((feature, gateways) in mapOf(
            "structure" to listOf("structure.psi", "structure.java"),
            "localhistory" to listOf("localhistory.ide"),
            "vcshistory" to listOf("vcshistory.vcs"),
        )) {
            val useCases = filesIn(feature).filterNot { file -> gateways.any { file in filesIn(it) } }
            Assertions.assertTrue(useCases.isNotEmpty(), "no $feature use-case files found")
            useCases.assertFalse { it.importsAny(gateways.map { "$root.$it" } + "com.intellij") }
        }
    }

    @Test
    fun `the language-neutral structure gateway does not use the JVM facts`() {
        filesIn("structure.psi").assertFalse { it.importsAny(listOf("$root.structure.java", "org.jetbrains.uast")) }
    }

    @Test
    fun `the Structure view API is used only by the structure gateway`() {
        assertOnlyIn(
            filesIn("structure.psi"),
            listOf("com.intellij.ide.structureView", "com.intellij.lang.LanguageStructureViewBuilder"),
        )
    }

    @Test
    fun `the Local History API is used only by its gateway, and never its implementation`() {
        assertOnlyIn(filesIn("localhistory.ide"), listOf("com.intellij.history"))
        production.files.assertFalse {
            it.importsAny(
                listOf("com.intellij.history.core", "com.intellij.history.integration", "com.intellij.platform.lvcs"),
            )
        }
    }

    @Test
    fun `VCS APIs are used only in vcs packages`() {
        val vcsPackages = production.files.filter { file -> file.packagee?.name.orEmpty().split('.').contains("vcs") }
        assertOnlyIn(vcsPackages, listOf("com.intellij.openapi.vcs", "com.intellij.vcsUtil", "git4idea"))
    }

    @Test
    fun `the diff rendering is shared and platform-free`() {
        val pure = filesIn("common.diff").filterNot { it.packagee?.name.orEmpty().endsWith(".ide") }
        Assertions.assertTrue(pure.isNotEmpty(), "no common.diff files found")
        pure.assertFalse { it.importsAny(listOf("com.intellij")) }
        assertOnlyIn(filesIn("common.diff.ide"), listOf("com.intellij.diff.comparison"))
    }
}
