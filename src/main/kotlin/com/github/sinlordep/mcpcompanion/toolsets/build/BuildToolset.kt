package com.github.sinlordep.mcpcompanion.toolsets.build

import com.github.sinlordep.mcpcompanion.build.BuildAction
import com.github.sinlordep.mcpcompanion.build.BuildArguments
import com.github.sinlordep.mcpcompanion.build.ide.buildControlFor
import com.github.sinlordep.mcpcompanion.common.result.map
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

/**
 * MCP adapter for `mc_build`. A tool of its own, not an action of `mc_modules`: building writes class files and runs
 * build tools, while `mc_modules` is annotated read-only, and MCP hints are per tool.
 */
class BuildToolset : CompanionToolset {

    override val toolsetId: String = ID

    @McpTool(name = "mc_build")
    @McpToolHints(
        readOnlyHint = McpToolHintValue.FALSE,
        // Writes only build output, never sources.
        destructiveHint = McpToolHintValue.FALSE,
        // A build runs the build tool's tasks again; with Gradle or Maven that may have effects of their own.
        idempotentHint = McpToolHintValue.FALSE,
        // Gradle and Maven may download dependencies and plugins.
        openWorldHint = McpToolHintValue.TRUE,
    )
    @McpDescription(description = DESCRIPTION)
    suspend fun build(
        @McpDescription(BuildParameterTexts.ACTION) action: BuildActionInput,
        @McpDescription(BuildParameterTexts.MODULE) module: String? = null,
        @McpDescription(BuildParameterTexts.INCLUDE_DEPENDENTS) includeDependents: Boolean? = null,
        @McpDescription(BuildParameterTexts.INCLUDE_WARNINGS) includeWarnings: Boolean? = null,
        @McpDescription(BuildParameterTexts.TIMEOUT) timeoutSeconds: Int? = null,
    ): BuildOutput {
        val project = currentCoroutineContext().project
        val arguments = BuildArguments(module, includeDependents, includeWarnings, timeoutSeconds)
        return buildControlFor(project).execute(action.action, arguments).map(BuildOutput::from).toMcpOutput()
    }

    companion object {
        const val ID: String = "build"

        private const val DESCRIPTION: String = "Builds one module and the modules it depends on through the " +
            "IDE's own build path (incremental; JPS, or Gradle/Maven when the project delegates building), instead " +
            "of the whole project. Actions: build - module (exact name from mc_modules list; Gradle modules are per " +
            "source set, e.g. app.main); includeDependents also builds every module that depends on it. Waits up to " +
            "timeoutSeconds (default 300, max 1800) and returns outcome succeeded|failed|aborted, the modules " +
            "included, durationMs and the errors (includeWarnings: also warnings) with path, line and column where " +
            "the build gives them: messages=compiler (the IDE's compiler, always with positions), build_output " +
            "(read from Gradle's or Maven's console: javac, kotlinc and Maven compiler lines), none (nothing " +
            "readable; build_project lists the Build window's messages). state=running means the timeout passed and " +
            "the build goes on: call status. status - the running build and the latest result. One build at a " +
            "time: a second build call is refused while one runs. Refused in an untrusted project; never asks or " +
            "opens a dialog (a running debug session is not offered a class reload). Check edits with mc_diagnostics " +
            "first: seconds, no build, the editor's errors also in dependent modules. Build when you need what only " +
            "a compile shows: compiler-only errors, annotation processors, generated code, build script and " +
            "dependency problems, or class files to run. Whole project: build_project."
    }
}

/** MCP input values of `action`. */
@Serializable
enum class BuildActionInput(val action: BuildAction) {
    @SerialName("build")
    BUILD(BuildAction.BUILD),

    @SerialName("status")
    STATUS(BuildAction.STATUS),
}

/** Parameter descriptions of `mc_build`: prompts for the agent, so short. */
private object BuildParameterTexts {
    const val ACTION = "build or status."
    const val MODULE = "build: the module, exact name."
    const val INCLUDE_DEPENDENTS = "build: also the modules that depend on it, transitively (default false)."
    const val INCLUDE_WARNINGS = "Also list warnings, not only errors (default false)."
    const val TIMEOUT = "build: seconds to wait, default 300, max 1800."
}
