package com.github.sinlordep.mcpcompanion.structuralsearch

import com.github.sinlordep.mcpcompanion.common.actions.ToolArguments

/** The actions of `mc_structural_search`. */
enum class StructuralSearchAction { SEARCH, TEMPLATES }

/** The arguments of `mc_structural_search` besides `action`; each action accepts a subset. */
enum class StructuralSearchParameter(val label: String) {
    PATTERN("pattern"),
    TEMPLATE("template"),
    FILE_TYPE("fileType"),
    SCOPE("scope"),
    CONSTRAINTS("constraints"),
    CASE_SENSITIVE("caseSensitive"),
    QUERY("query"),
    LIMIT("limit"),
    OFFSET("offset"),
}

/**
 * Raw client arguments of one `mc_structural_search` call, not yet validated. Null means "not given". In
 * [constraints], a `maxCount` of [UNBOUNDED_INPUT] stands for "no upper limit".
 */
data class StructuralSearchArguments(
    val pattern: String? = null,
    val template: String? = null,
    val fileType: String? = null,
    val scope: String? = null,
    val constraints: List<VariableConstraint>? = null,
    val caseSensitive: Boolean? = null,
    val query: String? = null,
    val limit: Int? = null,
    val offset: Int? = null,
) : ToolArguments<StructuralSearchParameter> {

    override fun given(): Set<StructuralSearchParameter> = buildSet {
        val values = mapOf(
            StructuralSearchParameter.PATTERN to pattern,
            StructuralSearchParameter.TEMPLATE to template,
            StructuralSearchParameter.FILE_TYPE to fileType,
            StructuralSearchParameter.SCOPE to scope,
            StructuralSearchParameter.CONSTRAINTS to constraints,
            StructuralSearchParameter.CASE_SENSITIVE to caseSensitive,
            StructuralSearchParameter.QUERY to query,
            StructuralSearchParameter.LIMIT to limit,
            StructuralSearchParameter.OFFSET to offset,
        )
        values.forEach { (parameter, value) -> if (value != null) add(parameter) }
    }

    companion object {
        const val UNBOUNDED_INPUT: Int = -1
    }
}
