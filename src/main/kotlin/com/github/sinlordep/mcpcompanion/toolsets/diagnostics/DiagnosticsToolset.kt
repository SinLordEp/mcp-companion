package com.github.sinlordep.mcpcompanion.toolsets.diagnostics

import com.github.sinlordep.mcpcompanion.common.build.ProblemSeverity
import com.github.sinlordep.mcpcompanion.common.modulegraph.GraphDirection
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.diagnostics.DiagnosticsArguments
import com.github.sinlordep.mcpcompanion.diagnostics.ide.diagnosticsFor
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.github.sinlordep.mcpcompanion.toolsets.toMcpOutput
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** MCP adapter for `mc_diagnostics`. Read-only. */
class DiagnosticsToolset : CompanionToolset {

    override val toolsetId: String = ID

    @McpTool(name = "mc_diagnostics")
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
        openWorldHint = McpToolHintValue.FALSE,
    )
    @McpDescription(description = DESCRIPTION)
    @Suppress("LongParameterList") // One parameter per MCP argument.
    suspend fun diagnostics(
        @McpDescription(DiagnosticsParameterTexts.MODULE) module: String? = null,
        @McpDescription(DiagnosticsParameterTexts.INCLUDE) include: RelatedModulesInput? = null,
        @McpDescription(DiagnosticsParameterTexts.DIRECTORY) directory: String? = null,
        @McpDescription(DiagnosticsParameterTexts.FILES) files: List<String>? = null,
        @McpDescription(DiagnosticsParameterTexts.MIN_SEVERITY) minSeverity: SeverityInput? = null,
        @McpDescription(DiagnosticsParameterTexts.LIMIT) limit: Int? = null,
        @McpDescription(DiagnosticsParameterTexts.OFFSET) offset: Int? = null,
        @McpDescription(DiagnosticsParameterTexts.TIMEOUT) timeoutSeconds: Int? = null,
    ): DiagnosticsOutput {
        val project = currentCoroutineContext().project
        val arguments = DiagnosticsArguments(
            module = module,
            related = include?.direction,
            directory = directory,
            files = files,
            minSeverity = minSeverity?.severity,
            limit = limit,
            offset = offset,
            timeoutSeconds = timeoutSeconds,
        )
        return diagnosticsFor(project).execute(arguments).map(DiagnosticsOutput::from).toMcpOutput()
    }

    companion object {
        const val ID: String = "diagnostics"

        private const val DESCRIPTION: String = "Problems in a scope of files without building: the IDE runs the " +
            "analysis an editor shows (the language's error checks and the project's enabled inspections) on files " +
            "that need not be open; external annotators (external linters, XML schema validation, Javadoc HTML " +
            "lint, KDoc links) are not run. Scope, exactly one: module (exact name from mc_modules list; include " +
            "adds the modules it depends on or that depend on it), directory, or files. minSeverity error (default) " +
            "or warning. Results grouped by file: line, column, severity, message, inspection id when the IDE names " +
            "it. errors, warnings and byModule count all analysed files. The scope's files are sorted; a call " +
            "analyses limit files (default 100, max 500) from offset within timeoutSeconds (default 60, max 600); " +
            "partial=timeout|indexing, hasMore and nextOffset say where to continue. Waits until the IDE finished " +
            "importing (Gradle, Maven) and indexing. Named files written outside the IDE are refreshed. Use it " +
            "first, after every edit: it takes seconds, builds nothing and also covers modules you would not build. " +
            "Build (mc_build, one module; build_project) when you need what only a compile shows: compiler limits " +
            "such as 'code too large', annotation processors and code generated during the build (missing " +
            "generated classes show here as errors), build script and dependency problems. One file in detail: " +
            "get_file_problems."
    }
}

/** MCP input values of `include`. */
@Serializable
enum class RelatedModulesInput(val direction: GraphDirection) {
    @SerialName("dependencies")
    DEPENDENCIES(GraphDirection.DEPENDENCIES),

    @SerialName("dependents")
    DEPENDENTS(GraphDirection.DEPENDENTS),
}

/** MCP input values of `minSeverity`. */
@Serializable
enum class SeverityInput(val severity: ProblemSeverity) {
    @SerialName("error")
    ERROR(ProblemSeverity.ERROR),

    @SerialName("warning")
    WARNING(ProblemSeverity.WARNING),
}

/** Parameter descriptions of `mc_diagnostics`: prompts for the agent, so short. */
private object DiagnosticsParameterTexts {
    const val MODULE = "Scope: the source files of this module (exact name)."
    const val INCLUDE = "With module: also the modules it depends on (dependencies) or that depend on it (dependents)."
    const val DIRECTORY = "Scope: project files under this directory (relative to the project directory, or absolute)."
    const val FILES = "Scope: these files (at most 500)."
    const val MIN_SEVERITY = "error (default) or warning (warnings and errors)."
    const val LIMIT = "Files to analyse in this call, 1-500, default 100."
    const val OFFSET = "First file of the scope to analyse (sorted by path); nextOffset of the previous call."
    const val TIMEOUT = "Seconds to analyse, default 60, max 600."
}
