package com.github.sinlordep.mcpcompanion.toolsets.usages

import com.github.sinlordep.mcpcompanion.testing.NavigationFixture
import com.github.sinlordep.mcpcompanion.testing.Position
import com.github.sinlordep.mcpcompanion.testing.ServedTools
import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.McpToolset
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** `mc_find_usages` end to end: toolset function, use case and platform gateway, inside an MCP call context. */
class FindUsagesToolsetTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<FindUsagesToolset>().single()

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

    private fun byName(name: String, access: AccessInput = AccessInput.ANY, scope: String? = null) =
        tools.call(TOOL) { toolset.findUsages(qualifiedName = name, access = access, scope = scope) }

    private fun at(position: Position, access: AccessInput = AccessInput.ANY, limit: Int? = null, offset: Int? = null) =
        tools.call(TOOL) {
            toolset.findUsages(
                position.path,
                position.line,
                position.column,
                access = access,
                limit = limit,
                offset = offset,
            )
        }

    private val radius = NavigationFixture.at("shapes/Circle.java", "radius;")

    fun testFindsReferencesToAClassWithLineTextAndEnclosingDeclaration() {
        val output = byName("shapes.Circle")
        assertEquals("shapes.Circle", output.symbol.qualifiedName)
        val usages = output.usages.sortedBy { it.line }
        assertEquals(listOf("app/Main.java:2", "app/Main.java:6"), usages.map { "${it.path}:${it.line}" })
        assertEquals("import shapes.Circle;", usages[0].lineText)
        assertNull(usages[0].enclosing)
        assertEquals("Shape s = new Circle(2);", usages[1].lineText)
        assertEquals("Main.total", usages[1].enclosing)
        assertEquals(NavigationFixture.at("app/Main.java", "Circle(2)").column, usages[1].column)
        assertFalse(output.hasMore)
        assertNull(output.nextOffset)
    }

    fun testFieldUsagesCarryTheirAccessKind() {
        val access = at(radius).usages.map { it.access }
        assertEquals(4, access.size)
        assertEquals(
            listOf(
                UsageOutput.AccessOutput.READ,
                UsageOutput.AccessOutput.READ,
                UsageOutput.AccessOutput.READ_WRITE,
                UsageOutput.AccessOutput.WRITE,
            ),
            access.sortedBy { it?.name },
        )
    }

    fun testAccessFilterKeepsReadsOrWritesAndCountsReadWriteForBoth() {
        assertEquals(
            setOf("radius = r;", "radius++;"),
            at(radius, AccessInput.WRITE).usages.map {
                it.lineText.substringAfter("{ ").substringBefore(" }")
            }.toSet(),
        )
        assertEquals(3, at(radius, AccessInput.READ).usages.size)
    }

    fun testAccessFilterIsUnsupportedForMethods() {
        assertEquals(
            "UNSUPPORTED",
            tools.errorCode(TOOL) {
                toolset.findUsages(qualifiedName = "shapes.Circle#grow", access = AccessInput.WRITE)
            },
        )
    }

    fun testPagesContinueAtNextOffsetWithoutGapsOrRepeats() {
        val first = at(radius, limit = 3)
        assertEquals(3, first.usages.size)
        assertTrue(first.hasMore)
        assertEquals(3, first.nextOffset)
        val second = at(radius, limit = 3, offset = 3)
        assertEquals(1, second.usages.size)
        assertFalse(second.hasMore)
        val all = (first.usages + second.usages).map { "${it.line}:${it.column}" }
        assertEquals(at(radius).usages.map { "${it.line}:${it.column}" }.toSet(), all.toSet())
        assertEquals(4, all.toSet().size)
    }

    fun testUnusedSymbolHasNoUsages() {
        val output = byName("shapes.Unused")
        assertEmpty(output.usages)
        assertFalse(output.hasMore)
    }

    fun testScopeLimitsTheSearch() {
        assertEquals(2, byName("shapes.Circle", scope = "dir:app").usages.size)
        assertEmpty(byName("shapes.Circle", scope = "dir:shapes").usages)
        assertEquals(2, byName("shapes.Circle", scope = "module:${module.name}").usages.size)
        assertEquals(2, byName("shapes.Circle", scope = "project_and_libraries").usages.size)
        assertEquals(
            "NOT_FOUND",
            tools.errorCode(TOOL) {
                toolset.findUsages(qualifiedName = "shapes.Circle", scope = "module:nope")
            },
        )
        assertEquals(
            "NOT_FOUND",
            tools.errorCode(TOOL) {
                toolset.findUsages(qualifiedName = "shapes.Circle", scope = "dir:nope")
            },
        )
        assertEquals(
            "INVALID_ARGUMENT",
            tools.errorCode(TOOL) {
                toolset.findUsages(qualifiedName = "shapes.Circle", scope = "galaxy")
            },
        )
    }

    fun testPositionOnAReferenceFindsTheSameSymbol() {
        val reference = NavigationFixture.at("app/Main.java", "Circle(2)", shift = 1)
        assertEquals("shapes.Circle", at(reference).symbol.qualifiedName)
    }

    fun testSymbolErrors() {
        assertEquals("NOT_FOUND", tools.errorCode(TOOL) { toolset.findUsages(qualifiedName = "shapes.Nope") })
        assertEquals("AMBIGUOUS", tools.errorCode(TOOL) { toolset.findUsages(qualifiedName = "shapes.Square#scale") })
        assertEquals("INVALID_ARGUMENT", tools.errorCode(TOOL) { toolset.findUsages() })
        assertEquals(
            "INVALID_ARGUMENT",
            tools.errorCode(TOOL) {
                toolset.findUsages(radius.path, radius.line, radius.column, qualifiedName = "shapes.Circle")
            },
        )
        assertEquals(
            "INVALID_ARGUMENT",
            tools.errorCode(TOOL) {
                toolset.findUsages(qualifiedName = "shapes.Circle", limit = 0)
            },
        )
    }

    /** Through the server's own tool object, with JSON arguments, as a client calls it. */
    fun testServerCallUsesLowercaseEnumValuesAndReturnsJson() {
        val result = tools.callJson(
            TOOL,
            buildJsonObject {
                put("path", JsonPrimitive(radius.path))
                put("line", radius.line)
                put("column", radius.column)
                put("access", "write")
            },
        )
        assertFalse(result.toString(), result.isError)
        val json = Json.parseToJsonElement(
            result.content.filterIsInstance<McpToolCallResultContent.Text>().single().text,
        )
        val usages = json.jsonObject.getValue("usages").jsonArray
        assertEquals(2, usages.size)
        assertEquals(
            setOf("write", "read_write"),
            usages.map {
                it.jsonObject.getValue("access").jsonPrimitive.content
            }.toSet(),
        )
    }

    fun testWaitsForIndexingInsteadOfFailing() {
        val token = DumbModeTestUtils.startEternalDumbModeTask(project)
        val call = try {
            val started = tools.start(TOOL) { toolset.findUsages(qualifiedName = "shapes.Circle") }
            ServedTools.settle()
            assertFalse("must wait while indexing", started.isCompleted)
            started
        } finally {
            DumbModeTestUtils.endEternalDumbModeTaskAndWaitForSmartMode(project, token)
        }
        assertEquals(2, tools.await(call).usages.size)
    }

    private companion object {
        const val TOOL = "mc_find_usages"
    }
}
