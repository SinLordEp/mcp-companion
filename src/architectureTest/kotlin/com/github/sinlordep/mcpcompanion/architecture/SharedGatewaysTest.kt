package com.github.sinlordep.mcpcompanion.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Rules for the platform APIs that one shared gateway owns (docs/PLAN.md section 5.1, docs/notes-step7.md): the
 * builds of `mc_run hotswap` and `mc_build`, the module graph of `mc_modules`, `mc_build` and `mc_diagnostics`, and
 * the optional VCS support behind `mc_diagnostics`.
 */
class SharedGatewaysTest {

    private val root = "com.github.sinlordep.mcpcompanion"

    /** APIs of the optional VCS support (`com.intellij.modules.vcs`); only `vcs` packages use them. */
    private val vcsApis = listOf("com.intellij.openapi.vcs", "com.intellij.codeInsight.CodeSmellInfo")

    /** The IDE's build API; only the shared build gateway (`common.build.java`) starts builds. */
    private val buildApis = listOf("com.intellij.task")

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
    fun `VCS APIs are used only in vcs packages`() {
        Assertions.assertTrue(filesIn("diagnostics.vcs").isNotEmpty(), "no diagnostics.vcs files found")
        production.files.filterNot { file -> file.packagee?.name.orEmpty().split('.').contains("vcs") }
            .assertFalse { it.importsAny(vcsApis) }
    }

    @Test
    fun `builds are started only by the shared build gateway`() {
        val gateway = filesIn("common.build.java")
        Assertions.assertTrue(gateway.isNotEmpty(), "no common.build.java files found")
        (production.files - gateway.toSet()).assertFalse { it.importsAny(buildApis) }
    }

    @Test
    fun `the module graph is shared through common, not read by each feature`() {
        val reader = filesIn("common.modulegraph.ide")
        Assertions.assertTrue(reader.isNotEmpty(), "no common.modulegraph.ide files found")
        (production.files - reader.toSet())
            .assertFalse { it.importsAny(listOf("com.intellij.openapi.roots.ModuleOrderEntry")) }
    }
}
