package com.github.sinlordep.mcpcompanion.diagnostics

import com.github.sinlordep.mcpcompanion.common.build.ProblemSeverity
import com.github.sinlordep.mcpcompanion.common.modulegraph.GraphDirection
import com.github.sinlordep.mcpcompanion.common.paging.Page

/** Which files `mc_diagnostics` analyses, as the client asked for them. */
sealed interface DiagnosticsScope {
    /** The source files of [name]; with [related] also those of the modules it depends on, or that depend on it. */
    data class Module(val name: String, val related: GraphDirection? = null) : DiagnosticsScope

    /** Every project file under [path], recursively. */
    data class Directory(val path: String) : DiagnosticsScope

    /** Exactly these files. */
    data class Files(val paths: List<String>) : DiagnosticsScope
}

/** The files the gateway resolves: the module scope already expanded to module names. */
sealed interface FileSelection {
    data class Modules(val names: List<String>) : FileSelection

    data class Directory(val path: String) : FileSelection

    data class Files(val paths: List<String>) : FileSelection
}

/** A file to analyse: [path] in the tool output form, [module] that owns it (null outside any module). */
data class SourceFile(val path: String, val module: String?)

/** One problem in a file; [line] and [column] 1-based; [inspection] the inspection's id when an inspection found it. */
data class Diagnostic(
    val severity: ProblemSeverity,
    val message: String,
    val line: Int,
    val column: Int,
    val inspection: String? = null,
)

/** What the analysis of one file gave. */
sealed interface FileAnalysis {
    val file: SourceFile

    data class Analysed(override val file: SourceFile, val problems: List<Diagnostic>) : FileAnalysis

    /** The IDE does not analyse this file (e.g. a binary file, or one the IDE excludes from batch analysis). */
    data class Skipped(override val file: SourceFile, val reason: String) : FileAnalysis
}

/** Why a call stopped before the end of its window of files. */
enum class PartialReason {
    /** timeoutSeconds passed while files were analysed. */
    TIMEOUT,

    /**
     * timeoutSeconds passed while the IDE was importing the project (Gradle, Maven) or indexing; analysis waits for
     * both, since results before them would be incomplete.
     */
    INDEXING,
}

/** Errors and warnings in some files. */
data class Totals(val files: Int, val errors: Int, val warnings: Int)

/**
 * The result of one `mc_diagnostics` call. [totalFiles] files are in the scope; this call analysed [analysed] of them
 * starting at the page's offset. [page] lists the analysed files that have a problem at or above [minSeverity]; its
 * next offset is the first file this call did not analyse. [partial] tells why it stopped early, if it did.
 */
data class DiagnosticsReport(
    val scope: DiagnosticsScope,
    val modules: List<String>,
    val minSeverity: ProblemSeverity,
    val totalFiles: Int,
    val analysed: Int,
    val page: Page<FileAnalysis.Analysed>,
    val partial: PartialReason?,
    val skipped: List<FileAnalysis.Skipped>,
    val totals: Totals,
    val byModule: Map<String, Totals>,
)
