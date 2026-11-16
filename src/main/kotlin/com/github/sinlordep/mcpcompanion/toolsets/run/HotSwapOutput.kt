package com.github.sinlordep.mcpcompanion.toolsets.run

import com.github.sinlordep.mcpcompanion.run.CompilerError
import com.github.sinlordep.mcpcompanion.run.HotSwapReport
import kotlinx.serialization.Serializable

/**
 * Result of `hotswap` for one debug session. outcome: reloaded, nothing_to_reload, compilation_failed (see errors),
 * rejected (the JVM refused; see messages) or not_possible. restarted: the process was restarted instead.
 */
@Serializable
data class HotSwapOutput(
    val session: String,
    val outcome: String,
    val classes: List<String> = emptyList(),
    val messages: List<String> = emptyList(),
    val errors: List<CompilerErrorOutput> = emptyList(),
    val restarted: Boolean = false,
) {
    companion object {
        fun from(report: HotSwapReport, restarted: Boolean): HotSwapOutput = when (report) {
            is HotSwapReport.Reloaded ->
                HotSwapOutput(report.session, "reloaded", report.classes, report.messages, restarted = restarted)

            is HotSwapReport.NothingToReload ->
                HotSwapOutput(report.session, "nothing_to_reload", messages = report.messages, restarted = restarted)

            is HotSwapReport.CompilationFailed -> HotSwapOutput(
                report.session,
                "compilation_failed",
                errors = report.errors.map(CompilerErrorOutput::from),
                restarted = restarted,
            )

            is HotSwapReport.Rejected ->
                HotSwapOutput(report.session, "rejected", report.classes, report.reasons, restarted = restarted)

            is HotSwapReport.NotPossible ->
                HotSwapOutput(report.session, "not_possible", messages = listOf(report.reason), restarted = restarted)
        }
    }
}

@Serializable
data class CompilerErrorOutput(
    val message: String,
    val path: String? = null,
    val line: Int? = null,
    val column: Int? = null,
) {
    companion object {
        fun from(error: CompilerError) = CompilerErrorOutput(error.message, error.path, error.line, error.column)
    }
}
