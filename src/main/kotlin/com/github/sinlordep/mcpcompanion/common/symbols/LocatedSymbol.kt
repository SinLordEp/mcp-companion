package com.github.sinlordep.mcpcompanion.common.symbols

/**
 * A symbol found by a [SymbolLocator], described without platform types.
 *
 * [handle] lets a later gateway get back to the underlying platform element; use cases pass it on without looking
 * inside.
 */
class LocatedSymbol(
    val name: String,
    val qualifiedName: String?,
    val location: SymbolLocation?,
    val handle: SymbolHandle,
) {
    override fun toString(): String = "LocatedSymbol($name, $qualifiedName, $location)"

    companion object {
        /** [name] of a declaration that has none, such as an anonymous class or a lambda. */
        const val ANONYMOUS: String = "<anonymous>"

        /** Reading order of the declarations; symbols without a location come last, then by name. */
        val ORDER: Comparator<LocatedSymbol> =
            compareBy<LocatedSymbol, SymbolLocation?>(nullsLast(SymbolLocation.ORDER)) { it.location }
                .thenBy { it.name }
    }
}

/**
 * A place in a file. [path] is rendered as the bundled MCP Server's tools render paths (see
 * `common.paths.ProjectPathRenderer`), so it can be passed to them; [line] and [column] are 1-based.
 */
data class SymbolLocation(val path: String, val line: Int, val column: Int, val origin: FileOrigin) {
    companion object {
        /** Reading order: by path, then line, then column. */
        val ORDER: Comparator<SymbolLocation> = compareBy({ it.path }, { it.line }, { it.column })
    }
}

/** What kind of file a location is in; tells an agent whether it may edit it. */
enum class FileOrigin {
    /** Project content (sources, tests, resources), in any content root. */
    PROJECT,

    /** Sources attached to a library. */
    LIBRARY_SOURCE,

    /** A library's compiled classes, shown decompiled. */
    LIBRARY_CLASS,

    /** The JDK or another SDK, sources or classes. */
    SDK,

    /** Anything else, e.g. a file outside the project that the IDE opened. */
    OTHER,
}

/** Opaque reference to the platform element behind a [LocatedSymbol]. Implemented by gateways only. */
interface SymbolHandle
