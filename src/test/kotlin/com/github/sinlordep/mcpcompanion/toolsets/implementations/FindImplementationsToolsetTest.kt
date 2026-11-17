package com.github.sinlordep.mcpcompanion.toolsets.implementations

import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.testing.NavigationFixture
import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.intellij.mcpserver.McpToolset
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** `mc_find_implementations` end to end, inside an MCP call context. */
class FindImplementationsToolsetTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<FindImplementationsToolset>().single()

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

    private fun down(name: String, scope: String? = null, limit: Int? = null, offset: Int? = null) = tools.call(TOOL) {
        toolset.findImplementations(qualifiedName = name, scope = scope, limit = limit, offset = offset)
    }

    private fun up(name: String) = tools.call(TOOL) {
        toolset.findImplementations(qualifiedName = name, direction = ImplementationDirectionInput.UP)
    }

    private fun FindImplementationsOutput.names() = results.map { it.qualifiedName ?: "${it.name}@${it.path}" }.toSet()

    fun testInterfaceHasItsImplementorsAtAnyDepthIncludingAnonymousClasses() {
        val output = down("shapes.Shape")
        assertEquals(
            setOf("shapes.Circle", "shapes.Square", "shapes.RoundedSquare", "${LocatedSymbol.ANONYMOUS}@app/Main.java"),
            output.names(),
        )
        val anonymous = output.results.single { it.name == LocatedSymbol.ANONYMOUS }
        assertEquals(NavigationFixture.at("app/Main.java", "Shape() {").line, anonymous.line)
    }

    fun testMethodHasItsOverridingAndImplementingMethods() {
        val output = down("shapes.Shape#area")
        assertEquals(4, output.results.size)
        assertEquals(setOf("area"), output.results.map { it.name }.toSet())
        assertEquals(
            setOf("shapes/Circle.java", "shapes/Square.java", "shapes/RoundedSquare.java", "app/Main.java"),
            output.results.map { it.path }.toSet(),
        )
        assertEquals(listOf("shapes/RoundedSquare.java"), down("shapes.Square#area").results.map { it.path })
    }

    fun testUpListsSupertypesAndSuperMethodsNearestFirst() {
        assertEquals(
            listOf("shapes.Square", "shapes.Shape"),
            up("shapes.RoundedSquare").results.map {
                it.qualifiedName
            },
        )
        assertEquals(
            listOf("shapes/Square.java", "shapes/Shape.java"),
            up("shapes.RoundedSquare#area").results.map { it.path },
        )
    }

    fun testUpIsUnsupportedForAField() {
        assertEquals(
            "UNSUPPORTED",
            tools.errorCode(TOOL) {
                toolset.findImplementations(
                    qualifiedName = "shapes.Square#side",
                    direction = ImplementationDirectionInput.UP,
                )
            },
        )
    }

    fun testPagingAndScope() {
        val first = down("shapes.Shape", limit = 3)
        assertTrue(first.hasMore)
        assertEquals(3, first.nextOffset)
        val second = down("shapes.Shape", limit = 3, offset = 3)
        assertEquals(1, second.results.size)
        assertFalse(second.hasMore)
        assertEquals(down("shapes.Shape").names(), first.names() + second.names())
        assertEquals(
            setOf("shapes.Circle", "shapes.Square", "shapes.RoundedSquare"),
            down("shapes.Shape", scope = "dir:shapes").names(),
        )
    }

    fun testPositionInputAndNoResults() {
        val square = NavigationFixture.at("shapes/Square.java", "Square implements", shift = 1)
        val output = tools.call(TOOL) { toolset.findImplementations(square.path, square.line, square.column) }
        assertEquals(setOf("shapes.RoundedSquare"), output.names())
        assertEmpty(down("shapes.Unused").results)
    }

    fun testSymbolErrors() {
        assertEquals("NOT_FOUND", tools.errorCode(TOOL) { toolset.findImplementations(qualifiedName = "shapes.Nope") })
        assertEquals(
            "AMBIGUOUS",
            tools.errorCode(TOOL) {
                toolset.findImplementations(qualifiedName = "shapes.Square#scale")
            },
        )
        assertEquals(
            "INVALID_ARGUMENT",
            tools.errorCode(TOOL) {
                toolset.findImplementations(path = "shapes/Square.java")
            },
        )
    }

    private companion object {
        const val TOOL = "mc_find_implementations"
    }
}
