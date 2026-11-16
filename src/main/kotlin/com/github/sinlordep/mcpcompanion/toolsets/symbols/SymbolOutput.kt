package com.github.sinlordep.mcpcompanion.toolsets.symbols

import com.github.sinlordep.mcpcompanion.common.symbols.FileOrigin
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A symbol in MCP output. [path] is in the form the bundled MCP Server's tools use (relative to the project directory,
 * `..` allowed; archive entries and other files absolute), so it can be passed to them; [line] and [column] are
 * 1-based.
 */
@Serializable
data class SymbolOutput(
    val name: String,
    val qualifiedName: String? = null,
    val path: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val origin: OriginOutput? = null,
) {
    companion object {
        fun from(symbol: LocatedSymbol): SymbolOutput = SymbolOutput(
            name = symbol.name,
            qualifiedName = symbol.qualifiedName,
            path = symbol.location?.path,
            line = symbol.location?.line,
            column = symbol.location?.column,
            origin = symbol.location?.origin?.let(OriginOutput::of),
        )
    }
}

/** MCP output values of a location's `origin`. */
@Serializable
enum class OriginOutput {
    @SerialName("project")
    PROJECT,

    @SerialName("library_source")
    LIBRARY_SOURCE,

    @SerialName("library_class")
    LIBRARY_CLASS,

    @SerialName("sdk")
    SDK,

    @SerialName("other")
    OTHER,
    ;

    companion object {
        fun of(origin: FileOrigin): OriginOutput = when (origin) {
            FileOrigin.PROJECT -> PROJECT
            FileOrigin.LIBRARY_SOURCE -> LIBRARY_SOURCE
            FileOrigin.LIBRARY_CLASS -> LIBRARY_CLASS
            FileOrigin.SDK -> SDK
            FileOrigin.OTHER -> OTHER
        }
    }
}
