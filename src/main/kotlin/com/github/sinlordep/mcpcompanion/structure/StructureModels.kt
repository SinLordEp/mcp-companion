package com.github.sinlordep.mcpcompanion.structure

import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/**
 * One element of a file's structure, as the IDE's Structure view shows it: a class, method, field, constructor, nested
 * class, JSON property, XML tag, Markdown header, ... [name] is the element's own name, [signature] the Structure
 * view's text when it says more (parameters and return type, a field's type); [location] the view's grey text (e.g.
 * the overridden type, a JSON value). [kind] is the language's term for the element, [modifiers] its visibility and
 * modifiers where the language support knows them. Lines are 1-based: [line] is the name's line, [startLine] and
 * [endLine] the element's whole range (comments and annotations included); null when the element has no position in
 * the file.
 */
data class OutlineElement(
    val name: String,
    val kind: String?,
    val signature: String?,
    val location: String?,
    val modifiers: List<String>,
    val line: Int?,
    val startLine: Int?,
    val endLine: Int?,
    val children: List<OutlineElement>,
)

/**
 * The structure of the file at [path]: its top-level elements with their children. [complete] is false when the tree
 * was cut after the gateway's element limit.
 */
data class FileOutline(
    val path: String,
    val language: String,
    val elements: List<OutlineElement>,
    val complete: Boolean,
)

/** Gateway boundary: the IDE's Structure view model of one file, built without an editor. */
fun interface StructureGateway {

    /** The structure of [path], with at most [maxElements] elements (counted in pre-order). */
    suspend fun outline(path: String, maxElements: Int): ToolResult<FileOutline>
}
