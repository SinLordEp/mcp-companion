package com.github.sinlordep.mcpcompanion.toolsets.structure

import com.github.sinlordep.mcpcompanion.structure.OutlineEntry
import com.github.sinlordep.mcpcompanion.structure.StructureOutcome
import kotlinx.serialization.Serializable

/**
 * MCP output model of `mc_file_structure`: one page of the [total] elements that pass the filters, in source order.
 * [complete] is false when the file has more elements than one call reads.
 */
@Serializable
data class FileStructureOutput(
    val path: String,
    val language: String,
    val total: Int,
    val elements: List<StructureElementOutput>,
    val hasMore: Boolean,
    val nextOffset: Int? = null,
    val complete: Boolean = true,
) {
    companion object {
        fun from(outcome: StructureOutcome) = FileStructureOutput(
            path = outcome.path,
            language = outcome.language,
            total = outcome.total,
            elements = outcome.page.items.map(StructureElementOutput::from),
            hasMore = outcome.page.hasMore,
            nextOffset = outcome.page.nextOffset,
            complete = outcome.complete,
        )
    }
}

/** One element; lines are 1-based, [children] counts all its children. */
@Serializable
data class StructureElementOutput(
    val id: String,
    val depth: Int,
    val name: String,
    val kind: String? = null,
    val signature: String? = null,
    val location: String? = null,
    val modifiers: List<String>? = null,
    val line: Int? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val children: Int = 0,
) {
    companion object {
        fun from(entry: OutlineEntry): StructureElementOutput {
            val element = entry.element
            return StructureElementOutput(
                id = entry.id,
                depth = entry.depth,
                name = element.name,
                kind = element.kind,
                signature = element.signature,
                location = element.location,
                modifiers = element.modifiers.ifEmpty { null },
                line = element.line,
                startLine = element.startLine,
                endLine = element.endLine,
                children = entry.childCount,
            )
        }
    }
}
