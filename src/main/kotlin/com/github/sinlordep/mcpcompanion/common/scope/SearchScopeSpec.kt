package com.github.sinlordep.mcpcompanion.common.scope

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/** Where a search looks, as a client asked for it. Platform-free; gateways turn it into a platform search scope. */
sealed interface SearchScopeSpec {

    /** Project content in all content roots (also those outside the project directory), tests included. */
    data object Project : SearchScopeSpec

    /** Project sources plus libraries and the JDK. */
    data object ProjectAndLibraries : SearchScopeSpec

    /** The content of one module, without its dependencies. */
    data class Module(val name: String) : SearchScopeSpec

    /** Everything under a directory, recursively. [path] is project-relative or absolute. */
    data class Directory(val path: String) : SearchScopeSpec

    companion object {
        /** Client syntax, also used in tool descriptions. */
        const val SYNTAX: String =
            "'project' (default; all content roots), 'project_and_libraries', 'module:<name>' or 'dir:<path>'"

        private const val MODULE_PREFIX = "module:"
        private const val DIRECTORY_PREFIX = "dir:"

        /** Parses the client syntax; null or blank means [Project]. */
        fun parse(text: String?): ToolResult<SearchScopeSpec> {
            val value = text?.trim().orEmpty()
            val argument = value.substringAfter(':', "").trim()
            return when {
                value.isEmpty() || value == "project" -> ToolResult.success(Project)

                value == "project_and_libraries" -> ToolResult.success(ProjectAndLibraries)

                value.startsWith(MODULE_PREFIX) && argument.isNotEmpty() -> ToolResult.success(Module(argument))

                value.startsWith(DIRECTORY_PREFIX) && argument.isNotEmpty() -> ToolResult.success(Directory(argument))

                else -> ToolResult.failure(
                    ErrorCode.INVALID_ARGUMENT,
                    "Unknown scope '$value'.",
                    "Use $SYNTAX.",
                )
            }
        }
    }
}
