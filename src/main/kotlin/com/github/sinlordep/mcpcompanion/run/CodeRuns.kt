package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/**
 * Code to run without a run configuration made beforehand: what the IDE's Run gutter icon (or Run in a file's or
 * directory's context menu) would run there.
 */
sealed interface CodeLocation {

    /** How the location reads in messages and in the user's confirmation. */
    val text: String

    /**
     * A file or directory, project-relative or absolute. With [line] (1-based), the code at that line of a file: its
     * first non-blank character, or [column] (1-based). Without, the file or the directory as a whole.
     */
    data class InFile(val path: String, val line: Int?, val column: Int?) : CodeLocation {
        override val text: String
            get() = buildString {
                append(path)
                line?.let { append(':').append(it) }
                column?.let { append(':').append(it) }
            }
    }

    /** A class or a member by qualified name: `com.example.FooTest`, `com.example.FooTest#adds`. */
    data class ByQualifiedName(val qualifiedName: String) : CodeLocation {
        override val text: String get() = qualifiedName
    }

    companion object {
        private const val HOW = "Pass path (a file or directory; with line, and optionally column, for the code at " +
            "that line) or qualifiedName (a class, or a member as com.example.FooTest#adds)."

        /** Validates the client's way of naming the code: [path] (+ [line], [column]) or [qualifiedName]. */
        fun of(path: String?, line: Int?, column: Int?, qualifiedName: String?): ToolResult<CodeLocation> {
            val file = path?.trim()?.takeIf { it.isNotEmpty() }
            val name = qualifiedName?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                name == null -> inFile(file, line, column)

                file != null || line != null || column != null -> ToolResult.failure(
                    ErrorCode.INVALID_ARGUMENT,
                    "Both a path and a qualifiedName were given.",
                    HOW,
                )

                else -> ToolResult.success(ByQualifiedName(name))
            }
        }

        private fun inFile(file: String?, line: Int?, column: Int?): ToolResult<CodeLocation> = when {
            file == null -> ToolResult.failure(ErrorCode.INVALID_ARGUMENT, "No code location was given.", HOW)

            line == null && column != null -> ToolResult.failure(ErrorCode.INVALID_ARGUMENT, "column needs line.", HOW)

            (line ?: 1) < 1 || (column ?: 1) < 1 -> ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "line and column are 1-based.",
                "Use the numbers an editor shows.",
            )

            else -> ToolResult.success(InFile(file, line, column))
        }
    }
}

/** Opaque reference to the IDE's run configuration behind a [CodeConfiguration]. Implemented by gateways only. */
interface CodeConfigurationHandle

/**
 * A run configuration the IDE derives from code. [existing]: the project already has it (saved or temporary), and it
 * is run as it is; otherwise a run creates it as a temporary configuration. [refusal]: why it cannot be run without
 * the IDE asking the user something (e.g. which subclass of an abstract test class to run); null when it can.
 */
class CodeConfiguration(
    val configuration: ConfigurationRef,
    val existing: Boolean,
    val refusal: String?,
    val handle: CodeConfigurationHandle,
) {
    override fun toString(): String = "CodeConfiguration(${configuration.name}, ${configuration.typeId}, $existing)"
}

/** Gateway boundary: run configurations from code, as the IDE's Run gutter icon makes them, never asking anything. */
interface CodeRunGateway {

    /** The run configurations the IDE offers for [location], the preferred ones only, in the IDE's order. */
    suspend fun configurationsAt(location: CodeLocation): ToolResult<List<CodeConfiguration>>

    /**
     * The project's configuration for [candidate]: an existing one as it is; a new one is added as a temporary
     * configuration, which the IDE keeps among its recent temporary ones and drops when newer ones replace it.
     */
    suspend fun register(candidate: CodeConfiguration): ToolResult<ConfigurationRef>

    /** Removes the temporary [configuration] that [register] added for a launch that did not happen. */
    suspend fun unregister(configuration: ConfigurationRef)
}
