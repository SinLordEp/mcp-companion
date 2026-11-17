package com.github.sinlordep.mcpcompanion.diagnostics

import com.github.sinlordep.mcpcompanion.common.modulegraph.ProjectGraph
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/** Gateway boundary: the project's files and modules around an analysis. */
interface DiagnosticsEnvironment {
    /** The modules and their module dependencies, one consistent snapshot. */
    suspend fun moduleGraph(): ProjectGraph

    /**
     * The files of [selection], sorted by path, without duplicates: for modules their source content (sources, tests,
     * resources), for a directory the project files under it; never excluded or binary files. Named files are looked
     * up on disk again first, so a file written outside the IDE is found. A failure names a file or directory that
     * does not exist.
     */
    suspend fun filesOf(selection: FileSelection): ToolResult<List<SourceFile>>

    /** The analyzer of this IDE; null when it has none (the platform's VCS support is missing). */
    fun analyzer(): FileAnalyzer?

    /** Suspends until the IDE finished importing the project and its indexes are ready (smart mode); cancellable. */
    suspend fun awaitIndexes()
}

/** Gateway boundary: the IDE's own analysis of files, the highlighting an editor shows, without building. */
fun interface FileAnalyzer {
    /**
     * Analyses [files] and returns one result per file, or [Batch.Interrupted] when indexing began during the analysis
     * (the results would be incomplete). Cancellable.
     */
    suspend fun analyze(files: List<SourceFile>): Batch

    sealed interface Batch {
        data class Done(val results: List<FileAnalysis>) : Batch

        data object Interrupted : Batch
    }
}
