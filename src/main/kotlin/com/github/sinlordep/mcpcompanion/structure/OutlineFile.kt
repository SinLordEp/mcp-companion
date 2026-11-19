package com.github.sinlordep.mcpcompanion.structure

import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.paging.page
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map

/** Raw client arguments of `mc_file_structure`, not yet validated. Null means "not given". */
data class StructureArguments(
    val path: String?,
    val maxDepth: Int? = null,
    val kinds: List<String>? = null,
    val limit: Int? = null,
    val offset: Int? = null,
)

/**
 * One element of the outline in pre-order. [id] is its place in the whole tree (`2`, `2.3`, `2.3.1`: the third child
 * of the second top-level element, ...), independent of filters, so ids show the nesting; [depth] is 1 for top-level
 * elements; [childCount] counts all its children, also those cut by `maxDepth` or `kinds`.
 */
data class OutlineEntry(val id: String, val depth: Int, val element: OutlineElement, val childCount: Int)

/**
 * The answer of `mc_file_structure`: one [page] of the [total] entries that pass the filters. [complete] is false when
 * the file has more elements than one call reads.
 */
data class StructureOutcome(
    val path: String,
    val language: String,
    val total: Int,
    val page: Page<OutlineEntry>,
    val complete: Boolean,
)

/**
 * Use case of `mc_file_structure`: the outline of one file from the IDE's Structure view, flattened in source order,
 * optionally limited to a depth and to some kinds, one page at a time.
 */
class OutlineFile(private val gateway: StructureGateway) {

    suspend fun execute(arguments: StructureArguments): ToolResult<StructureOutcome> =
        validated(arguments).flatMap { (path, filter) ->
            gateway.outline(path, MAX_ELEMENTS).map { outline ->
                val entries = flatten(outline.elements, "", 1).filter(filter::keeps).toList()
                StructureOutcome(
                    path = outline.path,
                    language = outline.language,
                    total = entries.size,
                    page = entries.asSequence().page(filter.page),
                    complete = outline.complete,
                )
            }
        }

    private fun flatten(elements: List<OutlineElement>, prefix: String, depth: Int): Sequence<OutlineEntry> =
        elements.asSequence().flatMapIndexed { index, element ->
            val id = "$prefix${index + 1}"
            sequenceOf(OutlineEntry(id, depth, element, element.children.size)) +
                flatten(element.children, "$id.", depth + 1)
        }

    private data class Filter(val maxDepth: Int?, val kinds: Set<String>?, val page: PageRequest) {
        fun keeps(entry: OutlineEntry): Boolean = (maxDepth == null || entry.depth <= maxDepth) &&
            (kinds == null || entry.element.kind?.lowercase() in kinds)
    }

    private fun validated(arguments: StructureArguments): ToolResult<Pair<String, Filter>> {
        val path = arguments.path?.takeIf { it.isNotBlank() } ?: return ToolResult.failure(
            ErrorCode.INVALID_ARGUMENT,
            "path is required: the file whose structure to show.",
            "Pass the file's path relative to the project root, or an absolute path.",
        )
        val maxDepth = arguments.maxDepth
        if (maxDepth != null && maxDepth !in 1..MAX_DEPTH) {
            return ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "maxDepth must be between 1 and $MAX_DEPTH, was $maxDepth.",
                "Use 1 for the top-level elements only, 2 to add their members.",
            )
        }
        val kinds = arguments.kinds?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet()
        if (kinds != null && kinds.isEmpty()) {
            return ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "kinds is empty.",
                "Omit kinds for every element, or pass kinds as an earlier answer shows them, e.g. [\"method\"].",
            )
        }
        return PageRequest.of(arguments.limit, arguments.offset, DEFAULT_LIMIT).map { page ->
            path to Filter(maxDepth, kinds, page)
        }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 200
        const val MAX_DEPTH: Int = 50

        /** Elements one call reads at most; larger files (e.g. huge JSON) are cut and reported as incomplete. */
        const val MAX_ELEMENTS: Int = 10_000
    }
}
