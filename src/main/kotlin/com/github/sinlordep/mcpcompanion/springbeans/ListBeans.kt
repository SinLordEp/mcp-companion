package com.github.sinlordep.mcpcompanion.springbeans

import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.paging.StableResults
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import java.util.regex.PatternSyntaxException

/**
 * `list`: the Spring beans of a module or of the whole project, optionally only those whose name or an alias contains
 * a text or matches a regular expression, and those of a type and its subtypes; sorted by name, one page at a time.
 */
internal class ListBeans(private val gateway: SpringBeansGateway) : SpringBeansActionHandler {

    override val action = SpringBeansAction.LIST

    override val parameters = setOf(
        SpringBeansParameter.MODULE,
        SpringBeansParameter.NAME,
        SpringBeansParameter.NAME_PATTERN,
        SpringBeansParameter.TYPE,
        SpringBeansParameter.INCLUDE_LIBRARIES,
        SpringBeansParameter.LIMIT,
        SpringBeansParameter.OFFSET,
    )

    override suspend fun handle(request: SpringBeansArguments): ToolResult<SpringBeansOutcome> =
        nameFilter(request).flatMap { keep ->
            PageRequest.of(request.limit, request.offset).flatMap { page ->
                val module = request.module.given()
                val includeLibraries = request.includeLibraries == true
                gateway.beans(BeanQuery(module, request.type.given())) { beans ->
                    val (shown, hidden) = beans.filter(keep).partition { includeLibraries || !it.library }
                    StableResults.page(shown, ORDER, page).map { PickedBeans(shown.size, hidden.size, it) }
                }.map { SpringBeansOutcome.Beans(module, it) }
            }
        }

    private fun nameFilter(request: SpringBeansArguments): ToolResult<(BeanSummary) -> Boolean> {
        val text = request.name.given()
        val pattern = request.namePattern.given()
        return when {
            text != null && pattern != null -> ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "Pass either name or namePattern, not both.",
                "name is a part of the name (any case); namePattern a regular expression.",
            )

            text != null -> ToolResult.success { bean -> bean.names.any { it.contains(text, ignoreCase = true) } }

            pattern != null -> try {
                val regex = Regex(pattern)
                ToolResult.success { bean -> bean.names.any { regex.containsMatchIn(it) } }
            } catch (e: PatternSyntaxException) {
                ToolResult.failure(
                    ErrorCode.INVALID_ARGUMENT,
                    "namePattern is not a valid regular expression: ${e.description}.",
                    "Write a Java regular expression, e.g. ^order.*Service$, or use name for a plain part of the name.",
                )
            }

            else -> ToolResult.success { true }
        }
    }

    companion object {
        /** By name ignoring case (anonymous beans last), then by the stable id (the place of the definition). */
        private val ORDER: Comparator<BeanSummary> =
            compareBy<BeanSummary, String?>(nullsLast()) { it.name?.lowercase() }
                .thenBy(nullsLast()) { it.name }
                .thenBy { it.id }
    }
}
