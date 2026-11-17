package com.github.sinlordep.mcpcompanion.toolsets.diagnostics

import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.intellij.codeInspection.deprecation.DeprecationInspection
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.McpToolset
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.siyeh.ig.controlflow.PointlessBooleanExpressionInspection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `mc_diagnostics` through the real gateways and the IDE's batch analysis (`CodeSmellDetector`), on files that are
 * not open in an editor: a compile error, a deprecation warning from an enabled inspection, a clean file.
 */
class DiagnosticsToolsetTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<DiagnosticsToolset>().single()

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(DeprecationInspection(), PointlessBooleanExpressionInspection())
        myFixture.addFileToProject(
            "diag/Broken.java",
            "package diag;\n\nclass Broken {\n    int size() {\n        return missing();\n    }\n}\n",
        )
        myFixture.addFileToProject(
            "diag/Warned.java",
            "package diag;\n\nclass Warned {\n" +
                "    void use() {\n        Old.old();\n    }\n\n" +
                "    boolean twice(boolean b) {\n        return b == true;\n    }\n}\n\n" +
                "class Old {\n    /** @deprecated */\n    static void old() {}\n}\n",
        )
        myFixture.addFileToProject("diag/Clean.java", "package diag;\n\nclass Clean {\n}\n")
        tools = ToolCalls(project)
    }

    override fun tearDown() {
        try {
            tools.dispose()
        } finally {
            super.tearDown()
        }
    }

    fun testADirectoryShowsTheCompileErrorWithItsPositionWithoutBuilding() {
        val output = tools.call(TOOL) { toolset.diagnostics(directory = "diag") }
        assertEquals("directory diag", output.scope)
        assertEquals(3, output.totalFiles)
        assertEquals(3, output.analysedFiles)
        val broken = output.files.single()
        assertEquals("diag/Broken.java", broken.path)
        val problem = broken.problems.single()
        assertEquals("error", problem.severity)
        assertEquals(5, problem.line)
        assertEquals(16, problem.column)
        assertTrue(problem.message, "missing" in problem.message)
        assertEquals(1, output.errors)
        assertNull(output.partial)
        assertFalse(output.hasMore)
    }

    fun testWarningsOfEnabledInspectionsOnRequest() {
        val output = tools.call(TOOL) {
            toolset.diagnostics(files = listOf("diag/Warned.java"), minSeverity = SeverityInput.WARNING)
        }
        val problems = output.files.single().problems
        assertEquals(listOf("warning", "warning"), problems.map { it.severity })
        assertEquals(listOf(5 to 13, 9 to 16), problems.map { it.line to it.column })
        assertEquals("'b == true' can be simplified to 'b'", problems[1].message)
        // The public batch analysis names the inspection only for findings of the "severity by key" kind; in 263
        // these inspections' findings are not of it (docs/notes-step7.md).
        assertEquals(listOf(null, null), problems.map { it.inspection })
        assertEquals(2, output.warnings)
        assertEquals(0, output.errors)
    }

    fun testUnknownFilesAndModulesAreNotFound() {
        assertEquals("NOT_FOUND", tools.errorCode(TOOL) { toolset.diagnostics(files = listOf("diag/Nope.java")) })
        assertEquals("NOT_FOUND", tools.errorCode(TOOL) { toolset.diagnostics(module = "nope") })
        assertEquals("NOT_FOUND", tools.errorCode(TOOL) { toolset.diagnostics(directory = "nope") })
        assertEquals("INVALID_ARGUMENT", tools.errorCode(TOOL) { toolset.diagnostics() })
    }

    fun testServerCallWithJsonArgumentsPagesTheFiles() {
        val result = tools.callJson(
            TOOL,
            JsonObject(
                mapOf(
                    "files" to JsonArray(listOf("diag/Clean.java", "diag/Broken.java").map(::JsonPrimitive)),
                    "limit" to JsonPrimitive(1),
                ),
            ),
        )
        assertFalse(result.toString(), result.isError)
        val json = Json.parseToJsonElement(
            result.content.filterIsInstance<McpToolCallResultContent.Text>().single().text,
        ).jsonObject
        assertEquals(2, json.getValue("totalFiles").jsonPrimitive.int)
        assertEquals(1, json.getValue("analysedFiles").jsonPrimitive.int)
        assertEquals(1, json.getValue("nextOffset").jsonPrimitive.int)
        // Sorted by path: Broken.java comes first and has the error.
        assertEquals(
            "diag/Broken.java",
            json.getValue("files").jsonArray.single().jsonObject["path"]!!.jsonPrimitive.content,
        )
    }

    private companion object {
        const val TOOL = "mc_diagnostics"
    }
}
