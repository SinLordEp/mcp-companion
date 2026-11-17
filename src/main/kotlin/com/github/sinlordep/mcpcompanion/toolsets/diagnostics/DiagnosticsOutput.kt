package com.github.sinlordep.mcpcompanion.toolsets.diagnostics

import com.github.sinlordep.mcpcompanion.diagnostics.Diagnostic
import com.github.sinlordep.mcpcompanion.diagnostics.DiagnosticsReport
import com.github.sinlordep.mcpcompanion.diagnostics.DiagnosticsScope
import com.github.sinlordep.mcpcompanion.diagnostics.FileAnalysis
import com.github.sinlordep.mcpcompanion.diagnostics.Totals
import kotlinx.serialization.Serializable

/**
 * MCP output model of `mc_diagnostics`. totalFiles: files in the scope; analysedFiles: analysed by this call, from
 * offset. files: the analysed files with problems at or above minSeverity. errors/warnings/byModule: counts over all
 * analysed files. partial: timeout or indexing when the call stopped early. Continue with offset=nextOffset while
 * hasMore.
 */
@Serializable
data class DiagnosticsOutput(
    val scope: String,
    val modules: List<String>? = null,
    val minSeverity: String,
    val totalFiles: Int,
    val analysedFiles: Int,
    val errors: Int,
    val warnings: Int,
    val byModule: List<ModuleTotalsOutput> = emptyList(),
    val files: List<FileDiagnosticsOutput>,
    val notAnalysed: List<SkippedFileOutput> = emptyList(),
    val partial: String? = null,
    val offset: Int,
    val hasMore: Boolean,
    val nextOffset: Int? = null,
) {
    companion object {
        fun from(report: DiagnosticsReport) = DiagnosticsOutput(
            scope = describe(report.scope),
            modules = report.modules.sorted().takeIf { it.isNotEmpty() },
            minSeverity = report.minSeverity.name.lowercase(),
            totalFiles = report.totalFiles,
            analysedFiles = report.analysed,
            errors = report.totals.errors,
            warnings = report.totals.warnings,
            byModule = report.byModule.map { (module, totals) -> ModuleTotalsOutput.from(module, totals) },
            files = report.page.items.map(FileDiagnosticsOutput::from),
            notAnalysed = report.skipped.take(MAX_SKIPPED).map { SkippedFileOutput(it.file.path, it.reason) },
            partial = report.partial?.name?.lowercase(),
            offset = report.page.offset,
            hasMore = report.page.hasMore,
            nextOffset = report.page.nextOffset,
        )

        private fun describe(scope: DiagnosticsScope): String = when (scope) {
            is DiagnosticsScope.Module ->
                "module ${scope.name}" + (scope.related?.let { " with its ${it.name.lowercase()}" } ?: "")

            is DiagnosticsScope.Directory -> "directory ${scope.path}"

            is DiagnosticsScope.Files -> "${scope.paths.size} files"
        }

        private const val MAX_SKIPPED = 50
    }
}

/** module: "" for files outside any module. */
@Serializable
data class ModuleTotalsOutput(val module: String, val files: Int, val errors: Int, val warnings: Int) {
    companion object {
        fun from(module: String, totals: Totals) =
            ModuleTotalsOutput(module, totals.files, totals.errors, totals.warnings)
    }
}

/** path as read_file takes it; problems sorted by position, at most 50 (omittedProblems counts the rest). */
@Serializable
data class FileDiagnosticsOutput(
    val path: String,
    val module: String? = null,
    val problems: List<ProblemOutput>,
    val omittedProblems: Int? = null,
) {
    companion object {
        fun from(file: FileAnalysis.Analysed) = FileDiagnosticsOutput(
            path = file.file.path,
            module = file.file.module,
            problems = file.problems.take(MAX_PROBLEMS).map(ProblemOutput::from),
            omittedProblems = (file.problems.size - MAX_PROBLEMS).takeIf { it > 0 },
        )

        private const val MAX_PROBLEMS = 50
    }
}

/** severity: error or warning; line and column 1-based; inspection: the inspection's id when one found it. */
@Serializable
data class ProblemOutput(
    val severity: String,
    val line: Int,
    val column: Int,
    val message: String,
    val inspection: String? = null,
) {
    companion object {
        fun from(problem: Diagnostic) = ProblemOutput(
            problem.severity.name.lowercase(),
            problem.line,
            problem.column,
            problem.message,
            problem.inspection,
        )
    }
}

@Serializable
data class SkippedFileOutput(val path: String, val reason: String)
