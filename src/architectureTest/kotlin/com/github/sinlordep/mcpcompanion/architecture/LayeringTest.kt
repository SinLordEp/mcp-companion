package com.github.sinlordep.mcpcompanion.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/** Layering rules of docs/PLAN.md section 5.1, checked on the production sources. */
class LayeringTest {

    private val root = "com.github.sinlordep.mcpcompanion"

    /** Feature (use-case) packages. They must not know about each other. */
    private val featurePackages = listOf("status", "navigation", "run", "modules", "sync")

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
    fun `the production scope is not empty`() {
        Assertions.assertTrue(filesIn("toolsets").isNotEmpty(), "Konsist found no sources; check the scope")
    }

    @Test
    fun `MCP adapters do not use PSI, execution or analysis APIs`() {
        val forbidden = listOf(
            "com.intellij.psi",
            "com.intellij.execution",
            "com.intellij.analysis",
            "com.intellij.codeInsight",
            "com.intellij.codeInspection",
        )
        filesIn("toolsets").assertFalse { it.importsAny(forbidden) }
    }

    @Test
    fun `feature packages do not depend on each other`() {
        for (feature in featurePackages) {
            val others = (featurePackages - feature).map { "$root.$it" }
            // Packages of later steps do not exist yet; Konsist refuses to assert on an empty list.
            val files = filesIn(feature)
            if (files.isNotEmpty()) files.assertFalse { it.importsAny(others) }
        }
    }

    @Test
    fun `common depends on no other package of the plugin`() {
        val others = (featurePackages + listOf("toolsets", "yield", "settings")).map { "$root.$it" }
        filesIn("common").assertFalse { it.importsAny(others) }
    }

    @Test
    fun `yield is used only by the adapter layer and the settings page`() {
        val allowed = filesIn("yield") + filesIn("toolsets") + filesIn("settings")
        (production.files - allowed.toSet()).assertFalse { it.importsAny(listOf("$root.yield")) }
    }

    @Test
    fun `nothing depends on the settings page`() {
        (production.files - filesIn("settings").toSet()).assertFalse { it.importsAny(listOf("$root.settings")) }
    }

    @Test
    fun `yield does not depend on the adapter layer`() {
        filesIn("yield").assertFalse { it.importsAny(listOf("$root.toolsets", "$root.settings")) }
    }

    @Test
    fun `main code never touches the MCP server implementation package`() {
        production.files.assertFalse { it.importsAny(listOf("com.intellij.mcpserver.impl")) }
    }

    @Test
    fun `main code does not use the undocumented tool disallow list or the tool filter extension point`() {
        val forbidden = listOf(
            "com.intellij.mcpserver.settings.McpToolDisallowListSettings",
            "com.intellij.mcpserver.McpToolFilterProvider",
        )
        production.files.assertFalse { it.importsAny(forbidden) }
    }
}
