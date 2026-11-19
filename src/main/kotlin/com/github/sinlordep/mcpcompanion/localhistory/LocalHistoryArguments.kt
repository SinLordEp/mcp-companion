package com.github.sinlordep.mcpcompanion.localhistory

import com.github.sinlordep.mcpcompanion.common.actions.ToolArguments

/** The actions of `mc_local_history`. */
enum class LocalHistoryAction { LABEL, LABELS, DIFF, PREVIEW, REVERT }

/** The arguments of `mc_local_history` besides `action`; each action accepts a subset. */
enum class LocalHistoryParameter(val label: String) {
    PATH("path"),
    NAME("name"),
    LABEL("label"),
    TIME("time"),
    MAX_LINES("maxLines"),
}

/** Raw client arguments of one `mc_local_history` call, not yet validated. Null means "not given". */
data class LocalHistoryArguments(
    val path: String? = null,
    val name: String? = null,
    val label: String? = null,
    val time: String? = null,
    val maxLines: Int? = null,
) : ToolArguments<LocalHistoryParameter> {
    override fun given(): Set<LocalHistoryParameter> = buildSet {
        val values = mapOf(
            LocalHistoryParameter.PATH to path,
            LocalHistoryParameter.NAME to name,
            LocalHistoryParameter.LABEL to label,
            LocalHistoryParameter.TIME to time,
            LocalHistoryParameter.MAX_LINES to maxLines,
        )
        values.forEach { (parameter, value) -> if (value != null) add(parameter) }
    }
}
