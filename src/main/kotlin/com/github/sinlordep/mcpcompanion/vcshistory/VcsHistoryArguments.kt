package com.github.sinlordep.mcpcompanion.vcshistory

import com.github.sinlordep.mcpcompanion.common.actions.ToolArguments

/** The actions of `mc_vcs_history`. */
enum class VcsHistoryAction { LOG, BLAME, DIFF }

/** The arguments of `mc_vcs_history` besides `action`; each action accepts a subset. */
enum class VcsHistoryParameter(val label: String) {
    PATH("path"),
    LINE("line"),
    END_LINE("endLine"),
    FROM("from"),
    TO("to"),
    MAX_LINES("maxLines"),
    LIMIT("limit"),
    OFFSET("offset"),
}

/** Raw client arguments of one `mc_vcs_history` call, not yet validated. Null means "not given". */
data class VcsHistoryArguments(
    val path: String? = null,
    val line: Int? = null,
    val endLine: Int? = null,
    val from: String? = null,
    val to: String? = null,
    val maxLines: Int? = null,
    val limit: Int? = null,
    val offset: Int? = null,
) : ToolArguments<VcsHistoryParameter> {
    override fun given(): Set<VcsHistoryParameter> = buildSet {
        val values = mapOf(
            VcsHistoryParameter.PATH to path,
            VcsHistoryParameter.LINE to line,
            VcsHistoryParameter.END_LINE to endLine,
            VcsHistoryParameter.FROM to from,
            VcsHistoryParameter.TO to to,
            VcsHistoryParameter.MAX_LINES to maxLines,
            VcsHistoryParameter.LIMIT to limit,
            VcsHistoryParameter.OFFSET to offset,
        )
        values.forEach { (parameter, value) -> if (value != null) add(parameter) }
    }
}
