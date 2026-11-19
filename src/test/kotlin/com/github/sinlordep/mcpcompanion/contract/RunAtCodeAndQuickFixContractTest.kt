package com.github.sinlordep.mcpcompanion.contract

import com.github.sinlordep.mcpcompanion.yield.reflectMcpToolNames
import com.intellij.mcpserver.McpToolSchema
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What `mc_run start_at` and `mc_quick_fix` rely on and what they leave to the bundled server
 * (docs/notes-step9.md). If one of these fails after an IDE update, redo that check before changing the expectation.
 */
class RunAtCodeAndQuickFixContractTest : BasePlatformTestCase() {

    private fun served(name: String) = McpServerService.getInstance().getMcpTools().single {
        it.descriptor.name == name
    }

    /** The official tools this IDE declares (also those it does not serve, e.g. ones behind a system property). */
    private fun officialTools(): Set<String> = McpToolset.EP.extensionList
        .filterNot { it.javaClass.name.startsWith("com.github.sinlordep.mcpcompanion") }
        .flatMap { reflectMcpToolNames(it.javaClass) }
        .toSortedSet()

    private fun servedOfficialTools(): Set<String> = McpServerService.getInstance().getMcpTools().map {
        it.descriptor.name
    }.filterNot { it.startsWith("mc_") }.toSet()

    /**
     * The official tools that take a location (`filePath` + `line`): run, debug and coverage run code from it;
     * `get_symbol_info` describes a symbol, `xdebug_run_to_line` continues a running debug session. None of the
     * runners returns a handle that later calls can stop, wait for, read or ask for test results, and none takes a
     * directory or a qualified name, so `start_at` is not a duplicate. A new one here may be: check it.
     */
    fun testTheOfficialToolsThatRunCodeFromALocationAreTheAnalysedOnes() {
        val fromLocation = servedOfficialTools().filter { name ->
            val properties = served(name).descriptor.inputSchema.propertiesSchema.keys
            "filePath" in properties && "line" in properties
        }.toSortedSet()
        assertEquals(
            sortedSetOf(
                "coverage_run_configuration",
                "execute_run_configuration",
                "get_symbol_info",
                "xdebug_run_to_line",
                "xdebug_start_debugger_session",
            ),
            fromLocation,
        )
        val run = served("execute_run_configuration").descriptor
        assertFalse("no process handle", propertyNames(run.outputSchema).any { it.contains("handle") || it == "id" })
    }

    /**
     * The official tools about code problems, fixes, intentions and inspections. `get_file_problems` and
     * `lint_files` report problems without fixes, `optimize_imports` never adds imports, the inspection.kts tools run
     * scripts an agent writes: nothing lists or applies the IDE's quick fixes. A new name here may duplicate
     * `mc_quick_fix`: check it, and add a catalog entry if it replaces the tool.
     */
    fun testTheOfficialProblemAndFixToolsAreTheAnalysedOnesAndReportNoFixes() {
        val related = Regex("fix|intention|problem|lint|inspection|import")
        assertEquals(
            sortedSetOf(
                "compile_inspection_kts",
                "generate_inspection_kts_api",
                "generate_inspection_kts_examples",
                "get_file_problems",
                "import_data",
                "inspect_import_file",
                "inspect_import_query",
                "lint_files",
                "optimize_imports",
                "run_inspection_kts",
                "run_inspection_kts_examples",
                "run_inspection_kts_project",
            ),
            officialTools().filter { related.containsMatchIn(it) }.toSortedSet(),
        )
        for (tool in listOf("get_file_problems", "lint_files")) {
            val output = propertyNames(served(tool).descriptor.outputSchema)
            assertFalse("$tool: $output", output.any { "fix" in it.lowercase() || "intention" in it.lowercase() })
        }
    }

    /** `mc_quick_fix` changes code but only project files, undoably, and never runs processes or network access. */
    fun testQuickFixHints() {
        val hints = served("mc_quick_fix").descriptor.annotations
        assertEquals(false, hints?.readOnlyHint)
        assertEquals(false, hints?.destructiveHint)
        assertEquals(false, hints?.openWorldHint)
    }

    /** Every property name in [schema], nested objects and definitions included. */
    private fun propertyNames(schema: McpToolSchema?): Set<String> {
        if (schema == null) return emptySet()
        val names = schema.propertiesSchema.keys.toMutableSet()
        (schema.propertiesSchema.values + schema.definitions.values).forEach { collect(it, names) }
        return names
    }

    private fun collect(element: JsonElement, names: MutableSet<String>) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                if (key == "properties" && value is JsonObject) names += value.keys
                collect(value, names)
            }

            is JsonArray -> element.forEach { collect(it, names) }

            else -> Unit
        }
    }
}
