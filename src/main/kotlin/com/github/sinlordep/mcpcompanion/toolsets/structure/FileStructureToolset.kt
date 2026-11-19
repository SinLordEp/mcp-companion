package com.github.sinlordep.mcpcompanion.toolsets.structure

import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.structure.StructureArguments
import com.github.sinlordep.mcpcompanion.structure.psi.outlineFileFor
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.github.sinlordep.mcpcompanion.toolsets.toMcpOutput
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext

/** MCP adapter for `mc_file_structure`. */
class FileStructureToolset : CompanionToolset {

    override val toolsetId: String = ID

    @McpTool(name = "mc_file_structure")
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
        openWorldHint = McpToolHintValue.FALSE,
    )
    @McpDescription(description = DESCRIPTION)
    suspend fun fileStructure(
        @McpDescription(StructureParameterTexts.PATH) path: String,
        @McpDescription(StructureParameterTexts.MAX_DEPTH) maxDepth: Int? = null,
        @McpDescription(StructureParameterTexts.KINDS) kinds: List<String>? = null,
        @McpDescription(StructureParameterTexts.LIMIT) limit: Int? = null,
        @McpDescription(StructureParameterTexts.OFFSET) offset: Int? = null,
    ): FileStructureOutput {
        val project = currentCoroutineContext().project
        val arguments = StructureArguments(path, maxDepth, kinds, limit, offset)
        return outlineFileFor(project).execute(arguments).map(FileStructureOutput::from).toMcpOutput()
    }

    companion object {
        const val ID: String = "file_structure"

        private const val DESCRIPTION: String = "Outline of one file from the IDE's Structure view, without reading " +
            "or opening the file: classes, methods, constructors, fields, nested classes (Java, Kotlin and every " +
            "other language with a Structure view: XML tags, JSON/YAML keys, Markdown headers, ...; library classes " +
            "too). Read it before reading a large file, then read_file only the lines you need. Each element: id " +
            "(its place in the tree: 2.3 is the 3rd child of the 2nd top-level element), depth (1 = top level), " +
            "name, kind (class, interface, enum, method, constructor, field, ... for JVM languages; otherwise the " +
            "language's own term, in the IDE's language), signature (parameters and return type, a field's type) " +
            "and location (e.g. the overridden type, a JSON value) when the view shows them, modifiers (JVM: " +
            "visibility, static, abstract, final fields), line (the name's line), startLine-endLine (the whole " +
            "element, comments and annotations included; 1-based), children (count of all children). Source " +
            "order; inherited members, anonymous classes and lambdas are not listed. maxDepth limits the depth, " +
            "kinds keeps only some kinds (as answers write them), limit/offset page (default 200, max 1000; " +
            "nextOffset). Files with more than 10000 elements are cut (complete=false). path as for read_file; " +
            "a file without a Structure view: UNSUPPORTED."
    }
}

/** Parameter descriptions of `mc_file_structure`: prompts for the agent, so short. */
private object StructureParameterTexts {
    const val PATH = "The file, relative to the project root or absolute (also a jar entry, as read_file takes)."
    const val MAX_DEPTH = "Deepest level to list, 1-50; 1 = top-level elements only. Default: all."
    const val KINDS = "Only elements of these kinds, e.g. [\"class\", \"method\"]. Default: all."
    const val LIMIT = "Elements per page, 1-1000, default 200."
    const val OFFSET = "First element of the page, default 0; continue with nextOffset."
}
