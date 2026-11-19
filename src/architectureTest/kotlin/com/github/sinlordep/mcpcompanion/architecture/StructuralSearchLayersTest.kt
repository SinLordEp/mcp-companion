package com.github.sinlordep.mcpcompanion.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Layers of `mc_structural_search` (docs/notes-step11.md): use cases at `structuralsearch`, the one gateway package
 * `structuralsearch.ssr` for the optional Structural Search plugin, search scopes, places and paging from `common`.
 */
class StructuralSearchLayersTest {

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

    @Test
    fun `use cases know neither the gateway package nor the platform`() {
        val gateway = filesIn("structuralsearch.ssr")
        val useCases = filesIn("structuralsearch") - gateway.toSet()
        Assertions.assertTrue(useCases.isNotEmpty(), "no structuralsearch use-case files found")
        useCases.assertFalse { it.importsAny(listOf("$root.structuralsearch.ssr", "com.intellij")) }
    }

    @Test
    fun `the Structural Search API is used only by its gateway`() {
        val gateway = filesIn("structuralsearch.ssr")
        Assertions.assertTrue(gateway.isNotEmpty(), "no structuralsearch.ssr files found")
        (production.files - gateway.toSet()).assertFalse { it.importsAny(listOf("com.intellij.structuralsearch")) }
    }

    @Test
    fun `the search reuses the shared scopes, places and paging instead of its own`() {
        val gateway = filesIn("structuralsearch.ssr")
        val shared = listOf("$root.common.scope", "$root.common.source", "$root.common.paging")
        Assertions.assertTrue(gateway.any { it.importsAny(shared) }, "the gateway no longer uses common")
    }
}
