package com.github.sinlordep.mcpcompanion.structuralsearch

import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.paging.page
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map

/**
 * `templates`: the search templates the IDE offers (built-in and saved), optionally only those whose name or category
 * contains a text and those of one language, sorted by language, category and name, one page at a time.
 */
internal class ListSearchTemplates(private val gateway: StructuralSearchGateway) : StructuralSearchActionHandler {

    override val action = StructuralSearchAction.TEMPLATES

    override val parameters = setOf(
        StructuralSearchParameter.QUERY,
        StructuralSearchParameter.FILE_TYPE,
        StructuralSearchParameter.LIMIT,
        StructuralSearchParameter.OFFSET,
    )

    override suspend fun handle(request: StructuralSearchArguments): ToolResult<StructuralSearchOutcome> =
        PageRequest.of(request.limit, request.offset, DEFAULT_LIMIT).flatMap { page ->
            val filter = TemplateFilter(
                query = request.query?.trim()?.takeIf { it.isNotEmpty() },
                fileType = request.fileType?.trim()?.takeIf { it.isNotEmpty() },
                page = page,
            )
            gateway.templates().map { templates ->
                val kept = templates.filter { it.passes(filter) }.sortedWith(ORDER)
                StructuralSearchOutcome.Templates(FoundTemplates(kept.size, kept.asSequence().page(filter.page)))
            }
        }

    private fun SearchTemplate.passes(filter: TemplateFilter): Boolean {
        val query = filter.query
        val fileType = filter.fileType
        val matchesQuery = query == null ||
            name.contains(query, ignoreCase = true) ||
            category.orEmpty().contains(query, ignoreCase = true)
        return matchesQuery && (fileType == null || this.fileType.equals(fileType, ignoreCase = true))
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 50

        private val ORDER: Comparator<SearchTemplate> = compareBy(
            { it.fileType.orEmpty().lowercase() },
            { it.category.orEmpty().lowercase() },
            { it.name.lowercase() },
        )
    }
}
