package com.github.sinlordep.mcpcompanion.toolsets.modules

import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.modules.ModulesArguments
import com.github.sinlordep.mcpcompanion.modules.ide.modulesControlFor
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.github.sinlordep.mcpcompanion.toolsets.toMcpOutput
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext

/** MCP adapter for `mc_modules`. Read-only. */
class ModulesToolset : CompanionToolset {

    override val toolsetId: String = ID

    @McpTool(name = "mc_modules")
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
        openWorldHint = McpToolHintValue.FALSE,
    )
    @McpDescription(description = DESCRIPTION)
    @Suppress("LongParameterList") // One parameter per MCP argument.
    suspend fun modules(
        @McpDescription(ModulesParameterTexts.ACTION) action: ModulesActionInput,
        @McpDescription(ModulesParameterTexts.MODULE) module: String? = null,
        @McpDescription(ModulesParameterTexts.DIRECTION) direction: DirectionInput? = null,
        @McpDescription(ModulesParameterTexts.DEPTH) depth: Int? = null,
        @McpDescription(ModulesParameterTexts.INCLUDE_LIBRARIES) includeLibraries: Boolean? = null,
        @McpDescription(ModulesParameterTexts.IGNORE_TEST_SCOPE) ignoreTestScope: Boolean? = null,
        @McpDescription(ModulesParameterTexts.FORMAT) format: FormatInput? = null,
        @McpDescription(ModulesParameterTexts.LIMIT) limit: Int? = null,
        @McpDescription(ModulesParameterTexts.OFFSET) offset: Int? = null,
    ): ModulesOutput {
        val project = currentCoroutineContext().project
        val arguments = ModulesArguments(
            module = module,
            direction = direction?.direction,
            depth = depth,
            includeLibraries = includeLibraries,
            ignoreTestScope = ignoreTestScope,
            format = format?.format,
            limit = limit,
            offset = offset,
        )
        return modulesControlFor(project).execute(action.action, arguments).map(ModulesOutput::from).toMcpOutput()
    }

    companion object {
        const val ID: String = "modules"

        private const val DESCRIPTION: String = "Reads the IDE's module model (read-only). Actions: list - every " +
            "module with type, buildSystem (gradle, maven, ... when imported), sdk, languageLevel and its content " +
            "roots with their folders (kind source, test, resource, test_resource, excluded; generated flag). Paths " +
            "are relative to the project directory as read_file takes them; a root next to it (e.g. a sibling " +
            "repository of a Gradle composite build) has a ../ path and outsideProjectDir=true, so this shows every " +
            "directory that belongs to the project. graph - declared dependency edges (from depends on to) with " +
            "scope (compile, test, runtime, provided) and exported; whole project, or with module a walk in " +
            "direction dependencies|dependents up to depth (default unlimited), each edge with its depth; " +
            "includeLibraries adds library edges with Maven coordinates when known; format=mermaid returns the " +
            "same edges as one Mermaid flowchart (field mermaid, not paged, at most 500 edges and 1000 modules; " +
            "omittedEdges, omittedNodes count the rest). cycles - groups of modules that depend on each other " +
            "(and self loops), each with one shortest cycle and all edges among them; ignoreTestScope leaves " +
            "test dependencies out. Lists are " +
            "sorted and paged (limit default 100, max 1000, offset; continue with nextOffset). Parameters that do " +
            "not apply to the action are rejected. Build one module: mc_build; whole project: build_project. " +
            "Problems without building: mc_diagnostics. After changing build files: mc_sync."
    }
}

/** Parameter descriptions of `mc_modules`: prompts for the agent, so short. */
private object ModulesParameterTexts {
    const val ACTION = "list, graph or cycles."
    const val MODULE = "list: only this module. graph: root of the walk. Exact module name."
    const val DIRECTION = "graph with module: dependencies (default) or dependents."
    const val DEPTH = "graph with module: at most this many edges from the root; default unlimited."
    const val INCLUDE_LIBRARIES = "graph: also library dependencies (default false)."
    const val IGNORE_TEST_SCOPE = "cycles: leave test-scope dependencies out (default false)."
    const val FORMAT = "graph: json (default, paged edges) or mermaid (one flowchart of all edges)."
    const val LIMIT = "Page size, 1-1000, default 100."
    const val OFFSET = "Start of the page; nextOffset of the previous page."
}
