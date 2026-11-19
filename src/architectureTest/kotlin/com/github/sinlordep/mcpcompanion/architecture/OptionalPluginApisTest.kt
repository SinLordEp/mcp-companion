package com.github.sinlordep.mcpcompanion.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * APIs of optional plugins used only in the gateway packages named after them (docs/PLAN.md section 5.1); using them
 * elsewhere breaks the plugin in IDEs without that plugin. The Java plugin's APIs are checked by `LayeringTest`.
 */
class OptionalPluginApisTest {

    /**
     * APIs of the optional test runner plugin (`com.intellij.platform.testRunner`: test trees, test consoles, test
     * states); only `smrunner` packages use them.
     */
    private val testRunnerApis = listOf("com.intellij.execution.testframework")

    /** APIs of the platform's optional external-system support; only `externalsystem` and `maven` packages use them. */
    private val externalSystemApis = listOf("com.intellij.openapi.externalSystem")

    /** APIs of the optional Maven plugin. */
    private val mavenPluginApis = listOf("org.jetbrains.idea.maven")

    private val production = Konsist.scopeFromProduction()

    private fun KoFileDeclaration.inSegment(segment: String): Boolean =
        packagee?.name.orEmpty().split('.').contains(segment)

    private fun KoFileDeclaration.importsAny(prefixes: List<String>): Boolean =
        imports.any { import -> prefixes.any { import.name == it || import.name.startsWith("$it.") } }

    @Test
    fun `external-system APIs are used only in externalsystem and maven packages`() {
        production.files.filterNot { it.inSegment("externalsystem") || it.inSegment("maven") }
            .assertFalse { it.importsAny(externalSystemApis) }
    }

    @Test
    fun `test runner APIs are used only in smrunner packages`() {
        Assertions.assertTrue(production.files.any { it.inSegment("smrunner") }, "no smrunner files found")
        production.files.filterNot { it.inSegment("smrunner") }.assertFalse { it.importsAny(testRunnerApis) }
    }

    @Test
    fun `Maven plugin APIs are used only in maven packages`() {
        production.files.filterNot { it.inSegment("maven") }.assertFalse { it.importsAny(mavenPluginApis) }
    }
}
