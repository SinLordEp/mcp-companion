package com.github.sinlordep.mcpcompanion.quickfix

import com.github.sinlordep.mcpcompanion.common.actions.ToolArguments
import com.github.sinlordep.mcpcompanion.common.build.ProblemSeverity

/** The actions of `mc_quick_fix`. */
enum class QuickFixAction { LIST, APPLY }

/** The arguments of `mc_quick_fix` besides `action`; each action accepts a subset. */
enum class QuickFixParameter(val label: String) {
    PATH("path"),
    LINE("line"),
    END_LINE("endLine"),
    MIN_SEVERITY("minSeverity"),
    FIX("fix"),
    LIMIT("limit"),
    OFFSET("offset"),
}

/** Raw client arguments of one `mc_quick_fix` call, not yet validated. Null means "not given". */
data class QuickFixArguments(
    val path: String? = null,
    val line: Int? = null,
    val endLine: Int? = null,
    val minSeverity: ProblemSeverity? = null,
    val fix: String? = null,
    val limit: Int? = null,
    val offset: Int? = null,
) : ToolArguments<QuickFixParameter> {
    override fun given(): Set<QuickFixParameter> = buildSet {
        val values = mapOf(
            QuickFixParameter.PATH to path,
            QuickFixParameter.LINE to line,
            QuickFixParameter.END_LINE to endLine,
            QuickFixParameter.MIN_SEVERITY to minSeverity,
            QuickFixParameter.FIX to fix,
            QuickFixParameter.LIMIT to limit,
            QuickFixParameter.OFFSET to offset,
        )
        values.forEach { (parameter, value) -> if (value != null) add(parameter) }
    }
}
