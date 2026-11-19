package com.github.sinlordep.mcpcompanion.structuralsearch

import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.paging.StableResults
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.scope.SearchScopeSpec
import com.github.sinlordep.mcpcompanion.common.source.SourceKey

/**
 * `search`: the code that matches a structural search template, one page at a time. Pages are cut from all matches
 * sorted by file and offset (see [StableResults]), so they are stable across calls; only the page is described.
 */
internal class SearchStructurally(private val gateway: StructuralSearchGateway) : StructuralSearchActionHandler {

    override val action = StructuralSearchAction.SEARCH

    override val parameters = setOf(
        StructuralSearchParameter.PATTERN,
        StructuralSearchParameter.TEMPLATE,
        StructuralSearchParameter.FILE_TYPE,
        StructuralSearchParameter.SCOPE,
        StructuralSearchParameter.CONSTRAINTS,
        StructuralSearchParameter.CASE_SENSITIVE,
        StructuralSearchParameter.LIMIT,
        StructuralSearchParameter.OFFSET,
    )

    override suspend fun handle(request: StructuralSearchArguments): ToolResult<StructuralSearchOutcome> =
        sourceOf(request).flatMap { source ->
            SearchScopeSpec.parse(request.scope).flatMap { scope ->
                VariableConstraints.validate(request.constraints.orEmpty()).flatMap { constraints ->
                    PageRequest.of(request.limit, request.offset, DEFAULT_LIMIT).flatMap { page ->
                        val query = StructuralQuery(
                            source = source,
                            fileType = request.fileType?.trim()?.takeIf { it.isNotEmpty() },
                            scope = scope,
                            constraints = constraints,
                            caseSensitive = request.caseSensitive,
                        )
                        gateway.search(query, StableResults.SEARCH_LIMIT) { keys ->
                            StableResults.page(keys, SourceKey.ORDER, page)
                        }.map { StructuralSearchOutcome.Matches(source, it) }
                    }
                }
            }
        }

    private fun sourceOf(request: StructuralSearchArguments): ToolResult<PatternSource> {
        val pattern = request.pattern?.takeIf { it.isNotBlank() }
        val template = request.template?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            pattern != null && template != null -> ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "Pass either pattern or template, not both.",
                "Use template alone to run a saved or built-in template; add constraints to refine its variables.",
            )

            pattern != null && pattern.length > MAX_PATTERN_LENGTH -> ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "pattern is longer than $MAX_PATTERN_LENGTH characters.",
                "Search for a smaller piece of code; variables ($" + "Name$) stand for the parts in between.",
            )

            pattern != null -> ToolResult.success(PatternSource.Text(pattern))

            template != null -> ToolResult.success(PatternSource.Template(template))

            else -> ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "pattern or template is required.",
                "Write the code to find with variables, e.g. pattern=\"$" + "Instance$.equals($" + "Arg$)\", or " +
                    "pass the name of a template that action=templates lists.",
            )
        }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 50
        const val MAX_PATTERN_LENGTH: Int = 10_000
    }
}
