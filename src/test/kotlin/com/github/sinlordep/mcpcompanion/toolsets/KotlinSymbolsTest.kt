package com.github.sinlordep.mcpcompanion.toolsets

import com.github.sinlordep.mcpcompanion.testing.NavigationFixture
import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.github.sinlordep.mcpcompanion.toolsets.hierarchy.HierarchyDirectionInput
import com.github.sinlordep.mcpcompanion.toolsets.hierarchy.TypeHierarchyToolset
import com.github.sinlordep.mcpcompanion.toolsets.implementations.FindImplementationsToolset
import com.github.sinlordep.mcpcompanion.toolsets.implementations.ImplementationDirectionInput
import com.github.sinlordep.mcpcompanion.toolsets.safedelete.SafeDeleteActionInput
import com.github.sinlordep.mcpcompanion.toolsets.safedelete.SafeDeleteToolset
import com.github.sinlordep.mcpcompanion.toolsets.usages.FindUsagesToolset
import com.intellij.mcpserver.McpToolset
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The four tools on Kotlin code (the Kotlin plugin is part of the test IDE), mixed with Java. */
class KotlinSymbolsTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls

    private inline fun <reified T : McpToolset> toolset(): T =
        McpToolset.EP.extensionList.filterIsInstance<T>().single()

    override fun setUp() {
        super.setUp()
        NavigationFixture.addTo(myFixture)
        myFixture.addFileToProject(PATH, ANIMALS)
        tools = ToolCalls(project)
    }

    override fun tearDown() {
        try {
            tools.dispose()
        } finally {
            super.tearDown()
        }
    }

    private fun at(snippet: String, shift: Int = 0): Triple<String, Int, Int> {
        val offset = ANIMALS.indexOf(snippet) + shift
        val before = ANIMALS.substring(0, offset)
        return Triple(PATH, before.count { it == '\n' } + 1, offset - (before.lastIndexOf('\n') + 1) + 1)
    }

    fun testFindUsagesOfAKotlinInterfaceByPositionAndOfJavaCodeFromKotlin() {
        val (path, line, column) = at("Animal {", shift = 1)
        val output = tools.call("mc_find_usages") { toolset<FindUsagesToolset>().findUsages(path, line, column) }
        assertEquals("Animal", output.symbol.name)
        assertEquals(PATH, output.symbol.path)
        // The reference sits in the type of parameter `a`; the enclosing declaration is the function, not `a`.
        assertEquals(setOf("Dog", "use"), output.usages.mapNotNull { it.enclosing }.toSet())

        val circle = tools.call("mc_find_usages") {
            toolset<FindUsagesToolset>().findUsages(qualifiedName = "shapes.Circle")
        }
        assertTrue(circle.usages.toString(), circle.usages.any { it.path == PATH && it.enclosing == "make" })
    }

    fun testKotlinPropertyAccessKinds() {
        val (path, line, column) = at("var legs", shift = 4)
        val output = tools.call("mc_find_usages") { toolset<FindUsagesToolset>().findUsages(path, line, column) }
        assertEquals(setOf("read", "write"), output.usages.mapNotNull { it.access?.name?.lowercase() }.toSet())
    }

    fun testImplementationsBothWaysAndByQualifiedNameThroughLightClasses() {
        val down = tools.call("mc_find_implementations") {
            toolset<FindImplementationsToolset>().findImplementations(qualifiedName = "kt.Animal")
        }
        assertEquals(PATH, down.symbol.path)
        assertEquals(listOf("Dog"), down.results.map { it.name })

        val (path, line, column) = at("sound() = ", shift = 0)
        val up = tools.call("mc_find_implementations") {
            toolset<FindImplementationsToolset>().findImplementations(
                path,
                line,
                column,
                direction = ImplementationDirectionInput.UP,
            )
        }
        assertEquals(listOf("sound"), up.results.map { it.name })
        assertEquals(PATH, up.results.single().path)
    }

    fun testTypeHierarchyOfKotlinAndJavaTypes() {
        val output = tools.call("mc_type_hierarchy") {
            toolset<TypeHierarchyToolset>().typeHierarchy(
                qualifiedName = "kt.Dog",
                direction = HierarchyDirectionInput.UP,
            )
        }
        assertEquals(listOf("kt.Animal"), output.types[0].supertypes?.map { output.types[it].qualifiedName })
        assertEquals(PATH, output.types.single { it.qualifiedName == "kt.Animal" }.path)

        val square = tools.call("mc_type_hierarchy") {
            toolset<TypeHierarchyToolset>().typeHierarchy(
                qualifiedName = "shapes.Square",
                direction = HierarchyDirectionInput.DOWN,
            )
        }
        assertTrue("a Kotlin subclass of a Java class", square.types.any { it.qualifiedName == "kt.Tile" })
    }

    fun testSafeDeleteOfKotlinDeclarations() {
        val used = tools.call("mc_safe_delete") { toolset<SafeDeleteToolset>().safeDelete(qualifiedName = "kt.Animal") }
        assertFalse(used.canDelete)

        val (path, line, column) = at("fun unused", shift = 4)
        val output = tools.call("mc_safe_delete") {
            toolset<SafeDeleteToolset>().safeDelete(SafeDeleteActionInput.APPLY, path, line, column)
        }
        assertTrue(output.deleted)
        assertFalse("unused" in myFixture.findFileInTempDir(PATH).let { myFixture.psiManager.findFile(it)!!.text })
    }

    private companion object {
        const val PATH = "kt/Animals.kt"
        val ANIMALS = """
            package kt

            interface Animal {
                fun sound(): String
            }

            class Dog : Animal {
                var legs = 4
                override fun sound() = "woof"
                fun walk() { legs = legs + 0 }
            }

            class Tile : shapes.Square()

            fun use(a: Animal) = a.sound()

            fun make() = shapes.Circle(1.0)

            fun unused() {}
        """.trimIndent()
    }
}
