package com.github.sinlordep.mcpcompanion.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Layers of `mc_spring_beans` (docs/notes-step13.md): use cases and the autowiring rules at `springbeans`, the one
 * gateway package `springbeans.spring` for the optional Spring plugin (a paid IntelliJ IDEA feature), places and paging
 * from `common`.
 */
class SpringBeansLayersTest {

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
        val gateway = filesIn("springbeans.spring")
        val useCases = filesIn("springbeans") - gateway.toSet()
        Assertions.assertTrue(useCases.isNotEmpty(), "no springbeans use-case files found")
        useCases.assertFalse { it.importsAny(listOf("$root.springbeans.spring", "com.intellij")) }
    }

    @Test
    fun `the Spring plugin's API is used only by its gateway`() {
        val gateway = filesIn("springbeans.spring")
        Assertions.assertTrue(gateway.isNotEmpty(), "no springbeans.spring files found")
        (production.files - gateway.toSet()).assertFalse {
            it.importsAny(listOf("com.intellij.spring", "com.intellij.microservices"))
        }
    }

    @Test
    fun `the gateway uses only the Spring plugin's public modules, not its internal ones`() {
        // intellij.spring.core.shared, the analysis modules and the microservices module are internal or not meant
        // for other plugins; the public bean model lives in com.intellij.spring and com.intellij.spring.model.
        filesIn("springbeans.spring").assertFalse {
            it.importsAny(listOf("com.intellij.spring.core", "com.intellij.microservices"))
        }
    }

    @Test
    fun `the gateway reuses the shared places and paths instead of its own`() {
        val gateway = filesIn("springbeans.spring")
        val shared = listOf("$root.common.source", "$root.common.paths")
        Assertions.assertTrue(gateway.any { it.importsAny(shared) }, "the gateway no longer uses common")
    }
}
