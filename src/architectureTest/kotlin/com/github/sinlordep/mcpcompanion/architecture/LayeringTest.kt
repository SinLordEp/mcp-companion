package com.github.sinlordep.mcpcompanion.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/** Layering rules of docs/PLAN.md section 5.1, checked on the production sources. */
class LayeringTest {

    private val root = "com.github.sinlordep.mcpcompanion"

    /**
     * Feature (use-case) packages. They must not know about each other. Each navigation tool is a feature of its own:
     * its toolset may step aside alone, so nothing may tie it to another navigation tool.
     */
    private val featurePackages = listOf(
        "status",
        "navigation.usages",
        "navigation.implementations",
        "navigation.hierarchy",
        "safedelete",
        "run",
        "modules",
        "sync",
    )

    /** Features built in the three layers of section 5.1: use cases at the root, gateways in `psi` and `java`. */
    private val layeredFeatures = listOf("navigation", "safedelete")

    /** Sub-package names that hold platform gateways. */
    private val gatewaySegments = setOf("psi", "java")

    /** APIs of the optional Java plugin; using them outside a `java` package breaks the plugin in non-Java IDEs. */
    private val javaPluginApis = listOf(
        "com.intellij.psi.JavaPsiFacade",
        "com.intellij.psi.PsiClass",
        "com.intellij.psi.PsiMethod",
        "com.intellij.psi.PsiField",
        "com.intellij.psi.PsiJavaFile",
        "com.intellij.psi.search.searches.ClassInheritorsSearch",
        "com.intellij.psi.search.searches.DirectClassInheritorsSearch",
        "com.intellij.psi.search.searches.OverridingMethodsSearch",
        "com.intellij.psi.search.searches.SuperMethodsSearch",
        "com.intellij.psi.util.InheritanceUtil",
        "com.intellij.lang.java",
        "org.jetbrains.uast",
    )

    private val production = Konsist.scopeFromProduction()

    private fun filesIn(subPackage: String): List<KoFileDeclaration> {
        val packageName = "$root.$subPackage"
        return production.files.filter { file ->
            val name = file.packagee?.name.orEmpty()
            name == packageName || name.startsWith("$packageName.")
        }
    }

    private fun KoFileDeclaration.isGateway(): Boolean =
        packagee?.name.orEmpty().split('.').any { it in gatewaySegments }

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
    fun `use cases and their models are platform-free`() {
        val platform = listOf("com.intellij", "org.jetbrains.uast", "org.jetbrains.kotlin.psi")
        val useCases = layeredFeatures.flatMap(::filesIn).filterNot { it.isGateway() }
        Assertions.assertTrue(useCases.isNotEmpty(), "no use-case files found; check layeredFeatures")
        useCases.assertFalse { it.importsAny(platform) }
    }

    @Test
    fun `the pure part of common is platform-free`() {
        filesIn("common").filterNot { it.isGateway() }.assertFalse { it.importsAny(listOf("com.intellij")) }
    }

    @Test
    fun `Java plugin APIs are used only in java packages`() {
        production.files.filterNot { file -> file.packagee?.name.orEmpty().split('.').contains("java") }
            .assertFalse { it.importsAny(javaPluginApis) }
    }

    @Test
    fun `common depends on no other package of the plugin`() {
        val others = (featurePackages + listOf("navigation", "toolsets", "yield", "settings")).map { "$root.$it" }
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
