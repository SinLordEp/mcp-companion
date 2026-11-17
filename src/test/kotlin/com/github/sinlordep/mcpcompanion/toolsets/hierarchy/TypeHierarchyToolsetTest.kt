package com.github.sinlordep.mcpcompanion.toolsets.hierarchy

import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.testing.NavigationFixture
import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.intellij.mcpserver.McpToolset
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** `mc_type_hierarchy` end to end, inside an MCP call context. */
class TypeHierarchyToolsetTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<TypeHierarchyToolset>().single()

    override fun setUp() {
        super.setUp()
        NavigationFixture.addTo(myFixture)
        tools = ToolCalls(project)
    }

    override fun tearDown() {
        try {
            tools.dispose()
        } finally {
            super.tearDown()
        }
    }

    private fun hierarchy(
        name: String,
        direction: HierarchyDirectionInput = HierarchyDirectionInput.BOTH,
        depth: Int? = null,
        maxNodes: Int? = null,
    ) = tools.call(TOOL) {
        toolset.typeHierarchy(qualifiedName = name, direction = direction, depth = depth, maxNodes = maxNodes)
    }

    private fun TypeHierarchyOutput.nameOf(id: Int) = types[id].qualifiedName ?: types[id].name

    private fun TypeHierarchyOutput.subtypesOf(name: String) =
        types.single { it.qualifiedName == name }.subtypes?.map { nameOf(it) }?.toSet()

    fun testInterfaceHasItsSubtypeTreeAndNoKnownSupertypes() {
        val output = hierarchy("shapes.Shape")
        assertEquals("shapes.Shape", output.types[0].qualifiedName)
        assertEquals(
            setOf("shapes.Circle", "shapes.Square", LocatedSymbol.ANONYMOUS),
            output.subtypesOf("shapes.Shape"),
        )
        assertEquals(setOf("shapes.RoundedSquare"), output.subtypesOf("shapes.Square"))
        assertEquals(emptySet<String>(), output.subtypesOf("shapes.RoundedSquare"))
        assertEquals(
            "no JDK in the light test project, so no java.lang.Object",
            emptyList<Int>(),
            output.types[0].supertypes,
        )
        assertFalse(output.truncated)
    }

    fun testSupertypeChainUpward() {
        val output = hierarchy("shapes.RoundedSquare", HierarchyDirectionInput.UP)
        assertEquals(listOf("shapes.Square"), output.types[0].supertypes?.map { output.nameOf(it) })
        val square = output.types.single { it.qualifiedName == "shapes.Square" }
        assertEquals(listOf("shapes.Shape"), square.supertypes?.map { output.nameOf(it) })
        assertNull("down was not asked for", output.types[0].subtypes)
        assertEquals("shapes/RoundedSquare.java", output.types[0].path)
    }

    fun testDepthAndNodeLimitsTruncate() {
        val shallow = hierarchy("shapes.Shape", HierarchyDirectionInput.DOWN, depth = 1)
        assertTrue(shallow.truncated)
        assertNull("beyond the depth limit", shallow.types.single { it.qualifiedName == "shapes.Square" }.subtypes)
        assertFalse(hierarchy("shapes.Shape", HierarchyDirectionInput.DOWN, depth = 2).truncated)

        val small = hierarchy("shapes.Shape", HierarchyDirectionInput.DOWN, maxNodes = 2)
        assertEquals(2, small.types.size)
        assertTrue(small.truncated)
    }

    fun testPositionInputAndErrors() {
        val circle = NavigationFixture.at("shapes/Circle.java", "Circle implements", shift = 2)
        val output = tools.call(TOOL) { toolset.typeHierarchy(circle.path, circle.line, circle.column) }
        assertEquals("shapes.Circle", output.types[0].qualifiedName)

        assertEquals(
            "UNSUPPORTED",
            tools.errorCode(TOOL) {
                toolset.typeHierarchy(qualifiedName = "shapes.Circle#grow")
            },
        )
        assertEquals("NOT_FOUND", tools.errorCode(TOOL) { toolset.typeHierarchy(qualifiedName = "shapes.Nope") })
        assertEquals(
            "INVALID_ARGUMENT",
            tools.errorCode(TOOL) {
                toolset.typeHierarchy(qualifiedName = "shapes.Shape", depth = 0)
            },
        )
        assertEquals(
            "INVALID_ARGUMENT",
            tools.errorCode(TOOL) {
                toolset.typeHierarchy(qualifiedName = "shapes.Shape", maxNodes = 5000)
            },
        )
    }

    private companion object {
        const val TOOL = "mc_type_hierarchy"
    }
}
