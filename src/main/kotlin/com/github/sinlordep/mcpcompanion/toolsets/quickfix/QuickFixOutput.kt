package com.github.sinlordep.mcpcompanion.toolsets.quickfix

import com.github.sinlordep.mcpcompanion.common.build.ProblemSeverity
import com.github.sinlordep.mcpcompanion.quickfix.AnalysedProblem
import com.github.sinlordep.mcpcompanion.quickfix.FixOption
import com.github.sinlordep.mcpcompanion.quickfix.ProblemFixes
import com.github.sinlordep.mcpcompanion.quickfix.QuickFixAction
import com.github.sinlordep.mcpcompanion.quickfix.QuickFixOutcome
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** MCP input values of `action`. */
@Serializable
enum class QuickFixActionInput(val action: QuickFixAction) {
    @SerialName("list")
    LIST(QuickFixAction.LIST),

    @SerialName("apply")
    APPLY(QuickFixAction.APPLY),
}

/** MCP input values of `minSeverity`. */
@Serializable
enum class QuickFixSeverityInput(val severity: ProblemSeverity) {
    @SerialName("error")
    ERROR(ProblemSeverity.ERROR),

    @SerialName("warning")
    WARNING(ProblemSeverity.WARNING),
}

/**
 * MCP output model of `mc_quick_fix`. After `list`: [problems] (one page of [problemCount]) with their fixes,
 * [hasMore] and [nextOffset]. After `apply`: [applied] (the fix), [problems] holds the problem it fixed (without
 * fixes), and [changedFiles].
 */
@Serializable
data class QuickFixOutput(
    val path: String,
    val problemCount: Int? = null,
    val problems: List<ProblemOutput>,
    val applied: FixOutput? = null,
    val changedFiles: List<String>? = null,
    val hasMore: Boolean = false,
    val nextOffset: Int? = null,
) {
    companion object {
        fun from(outcome: QuickFixOutcome): QuickFixOutput = when (outcome) {
            is QuickFixOutcome.Listed -> QuickFixOutput(
                path = outcome.path,
                problemCount = outcome.problemCount,
                problems = outcome.problems.items.map(ProblemOutput::from),
                hasMore = outcome.problems.hasMore,
                nextOffset = outcome.problems.nextOffset,
            )

            is QuickFixOutcome.Applied -> QuickFixOutput(
                path = outcome.path,
                problems = listOf(ProblemOutput.of(outcome.problem, null)),
                applied = FixOutput.from(outcome.fix),
                changedFiles = outcome.changedFiles,
            )
        }
    }
}

/** A problem; [fixes] is absent in the answer of `apply`. Lines and columns are 1-based. */
@Serializable
data class ProblemOutput(
    val line: Int,
    val column: Int,
    val endLine: Int,
    val endColumn: Int,
    /** error or warning (warnings and weak warnings). */
    val severity: String,
    val message: String,
    val lineText: String,
    val fixes: List<FixOutput>? = null,
) {
    companion object {
        fun from(problem: ProblemFixes): ProblemOutput = of(problem.problem, problem.fixes.map(FixOutput::from))

        fun of(problem: AnalysedProblem, fixes: List<FixOutput>?) = ProblemOutput(
            line = problem.line,
            column = problem.column,
            endLine = problem.endLine,
            endColumn = problem.endColumn,
            severity = problem.severity.name.lowercase(),
            message = problem.message,
            lineText = problem.lineText,
            fixes = fixes,
        )
    }
}

/** A fix: pass [id] to `apply`; when [applicable] is false, [reason] says why it needs the user. */
@Serializable
data class FixOutput(val id: String, val name: String, val applicable: Boolean, val reason: String? = null) {
    companion object {
        fun from(fix: FixOption) = FixOutput(fix.id.value, fix.name, fix.refusal == null, fix.refusal)
    }
}
