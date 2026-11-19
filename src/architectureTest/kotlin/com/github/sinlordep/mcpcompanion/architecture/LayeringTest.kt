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
        "move",
        "changesignature",
        "pullup",
        "run",
        "modules",
        "sync",
        "build",
        "diagnostics",
        "quickfix",
        "structure",
        "localhistory",
        "vcshistory",
        "structuralsearch",
    )

    /**
     * Features built in the three layers of section 5.1: use cases at the root (and in sub-packages such as
     * `run.output`), gateways in the [gatewaySegments] sub-packages.
     */
    private val layeredFeatures = listOf("navigation") + featurePackages.filterNot { it == "status" || "." in it }

    /** Layered features whose use cases sit at the feature root and whose gateways are `ide` plus optional segments. */
    private val gatewayFeatures = listOf("run", "modules", "sync", "build", "diagnostics")

    /**
     * Sub-package names that hold platform gateways: `psi` for code analysis, `ide` for other IDE services, and one
     * per optional plugin: `java` (Java plugin), `externalsystem` (the platform's optional external-system support:
     * Gradle and others), `maven` (Maven plugin), `smrunner` (the platform's test runner plugin), `vcs` (the platform's
     * VCS support, which holds the batch code analysis), `ssr` (the Structural Search plugin).
     */
    private val gatewaySegments = setOf("psi", "ide", "java", "externalsystem", "maven", "smrunner", "vcs", "ssr")

    /** Gateway segments that need an optional plugin; the neutral `ide` gateways must not depend on them. */
    private val optionalSegments = listOf("java", "externalsystem", "maven", "smrunner", "vcs", "ssr")

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
        "com.intellij.debugger",
        "com.intellij.openapi.compiler",
        "com.intellij.java.library",
        "com.intellij.openapi.module.LanguageLevelUtil",
        "com.intellij.pom.java",
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
    fun `use cases depend on no platform gateway of their own feature`() {
        for (feature in gatewayFeatures) {
            val gateways = gatewaySegments.map { "$root.$feature.$it" }
            val useCases = filesIn(feature).filterNot { it.isGateway() }
            Assertions.assertTrue(useCases.isNotEmpty(), "no $feature use-case files found")
            useCases.assertFalse { it.importsAny(gateways) }
        }
    }

    @Test
    fun `the platform-neutral gateways do not use the gateways of optional plugins`() {
        for (feature in gatewayFeatures) {
            val optional = optionalSegments.map { "$root.$feature.$it" }
            val neutral = filesIn("$feature.ide")
            Assertions.assertTrue(neutral.isNotEmpty(), "no $feature.ide files found")
            neutral.assertFalse { it.importsAny(optional) }
        }
    }

    @Test
    fun `the test results use case is a platform-free sub-package of run`() {
        val useCases = filesIn("run.tests")
        Assertions.assertTrue(useCases.isNotEmpty(), "no run.tests files found")
        useCases.assertFalse { it.importsAny(listOf("com.intellij", "$root.run.ide", "$root.run.smrunner")) }
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
    fun `the MCP Server's settings are read and written only by the confirmation setting gateway`() {
        val gateway = filesIn("common.consent.ide")
        Assertions.assertTrue(gateway.isNotEmpty(), "no common.consent.ide files found")
        (production.files - gateway.toSet())
            .assertFalse { it.importsAny(listOf("com.intellij.mcpserver.settings.McpServerSettings")) }
    }

    @Test
    fun `run use cases know the user's confirmation only through their gateway interface`() {
        val useCases = filesIn("run").filterNot { it.isGateway() }
        useCases.assertFalse { it.importsAny(listOf("$root.common.consent")) }
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
