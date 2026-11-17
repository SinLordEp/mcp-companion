package com.github.sinlordep.mcpcompanion.diagnostics

import com.github.sinlordep.mcpcompanion.common.build.ProblemSeverity
import com.github.sinlordep.mcpcompanion.common.modulegraph.DependencyGraph
import com.github.sinlordep.mcpcompanion.common.modulegraph.GraphDirection
import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.time.Clock
import com.github.sinlordep.mcpcompanion.common.time.timeoutOf
import com.github.sinlordep.mcpcompanion.common.time.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import java.time.Duration as JavaDuration

/** Raw client arguments of one `mc_diagnostics` call, not yet validated. Null means "not given". */
data class DiagnosticsArguments(
    val module: String? = null,
    val related: GraphDirection? = null,
    val directory: String? = null,
    val files: List<String>? = null,
    val minSeverity: ProblemSeverity? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val timeoutSeconds: Int? = null,
)

/**
 * Use case of `mc_diagnostics`: the problems the IDE's own analysis finds in a scope of files, without building.
 *
 * The files of the scope are sorted by path; one call analyses the window `offset .. offset+limit` (the hard bound
 * per call, at most [MAX_FILES]) in batches of [BATCH_SIZE], each after the indexes are ready. A batch that indexing
 * interrupted is analysed again. When the timeout passes, the call returns what it analysed, `partial` says why, and
 * the page's next offset is the first file not analysed, so the client continues there.
 */
class CollectDiagnostics(private val environment: DiagnosticsEnvironment, private val clock: Clock) {

    suspend fun execute(arguments: DiagnosticsArguments): ToolResult<DiagnosticsReport> =
        scopeOf(arguments).flatMap { scope ->
            window(arguments).flatMap { (offset, limit) ->
                timeoutOf(arguments.timeoutSeconds, DEFAULT_TIMEOUT, MAX_TIMEOUT).flatMap { timeout ->
                    val analyzer = environment.analyzer() ?: return@flatMap unsupported()
                    // The module model and the files are read once the IDE finished importing: before a Gradle or
                    // Maven import the modules and their source roots are not there yet.
                    val start = clock.now()
                    val ready = clock.withTimeout(timeout) { environment.awaitIndexes() } != null
                    val remaining = timeout - JavaDuration.between(start, clock.now()).toKotlinDuration()
                    selectionOf(scope).flatMap { (modules, selection) ->
                        environment.filesOf(selection).flatMap { files ->
                            val severity = arguments.minSeverity ?: ProblemSeverity.ERROR
                            val window = files.drop(offset).take(limit)
                            val run = if (ready) analyse(analyzer, window, remaining) else Run(PartialReason.INDEXING)
                            ToolResult.success(report(scope, modules, severity, files.size, offset, run))
                        }
                    }
                }
            }
        }

    /** The progress of one call: the files analysed so far, and whether it waits for the indexes right now. */
    private class Run(var partial: PartialReason? = null) {
        val results: MutableList<FileAnalysis> = mutableListOf()

        @Volatile
        var waitingForIndexes: Boolean = false
    }

    private suspend fun analyse(analyzer: FileAnalyzer, window: List<SourceFile>, timeout: Duration): Run {
        val run = Run()
        if (!timeout.isPositive()) return Run(PartialReason.TIMEOUT)
        val done = clock.withTimeout(timeout) {
            for (batch in window.chunked(BATCH_SIZE)) {
                while (true) {
                    run.waitingForIndexes = true
                    environment.awaitIndexes()
                    run.waitingForIndexes = false
                    val result = analyzer.analyze(batch)
                    if (result is FileAnalyzer.Batch.Done) {
                        synchronized(run) { run.results += result.results }
                        break
                    }
                }
            }
            true
        }
        if (done == null) run.partial = if (run.waitingForIndexes) PartialReason.INDEXING else PartialReason.TIMEOUT
        return run
    }

    private fun report(
        scope: DiagnosticsScope,
        modules: List<String>,
        severity: ProblemSeverity,
        totalFiles: Int,
        offset: Int,
        run: Run,
    ): DiagnosticsReport {
        val results = synchronized(run) { run.results.toList() }
        val analysed = results.filterIsInstance<FileAnalysis.Analysed>()
        val next = offset + results.size
        val listed = analysed
            .map { file -> file.copy(problems = file.problems.filter { it.severity <= severity }) }
            .filter { it.problems.isNotEmpty() }
        return DiagnosticsReport(
            scope = scope,
            modules = modules,
            minSeverity = severity,
            totalFiles = totalFiles,
            analysed = results.size,
            page = Page(listed, offset, hasMore = next < totalFiles, nextOffset = next.takeIf { it < totalFiles }),
            partial = run.partial,
            skipped = results.filterIsInstance<FileAnalysis.Skipped>(),
            totals = totalsOf(analysed),
            byModule = analysed.groupBy { it.file.module ?: NO_MODULE }.mapValues { (_, files) -> totalsOf(files) }
                .toSortedMap(),
        )
    }

    private fun totalsOf(files: List<FileAnalysis.Analysed>) = Totals(
        files = files.size,
        errors = files.sumOf { file -> file.problems.count { it.severity == ProblemSeverity.ERROR } },
        warnings = files.sumOf { file -> file.problems.count { it.severity == ProblemSeverity.WARNING } },
    )

    /** The module scope expands to its module names; directory and files go to the gateway as they are. */
    private suspend fun selectionOf(scope: DiagnosticsScope): ToolResult<Pair<List<String>, FileSelection>> =
        when (scope) {
            is DiagnosticsScope.Directory -> ToolResult.success(
                emptyList<String>() to FileSelection.Directory(scope.path),
            )

            is DiagnosticsScope.Files -> ToolResult.success(emptyList<String>() to FileSelection.Files(scope.paths))

            is DiagnosticsScope.Module -> {
                val graph = DependencyGraph(environment.moduleGraph())
                if (scope.name !in graph) {
                    ToolResult.failure(
                        ErrorCode.NOT_FOUND,
                        "No module named '${scope.name}'.",
                        "Use an exact module name from mc_modules list.",
                    )
                } else {
                    val names = scope.related
                        ?.let { graph.walk(scope.name, it, Int.MAX_VALUE).reached.toList() }
                        ?: listOf(scope.name)
                    ToolResult.success(names to FileSelection.Modules(names))
                }
            }
        }

    private fun scopeOf(arguments: DiagnosticsArguments): ToolResult<DiagnosticsScope> {
        val module = arguments.module?.takeIf { it.isNotBlank() }
        val directory = arguments.directory?.takeIf { it.isNotBlank() }
        val files = arguments.files?.map(String::trim)?.filter(String::isNotEmpty)?.distinct()
        val given = listOfNotNull(module, directory, files)
        return when {
            given.size != 1 -> invalid(
                "Give exactly one of module, directory or files.",
                "module=<exact name>, directory=<path> or files=[<path>, ...].",
            )

            arguments.related != null && module == null -> invalid(
                "include applies to module only.",
                "Give module, or omit include.",
            )

            files != null && files.size > MAX_FILES -> invalid(
                "At most $MAX_FILES files per call, got ${files.size}.",
                "Split the list, or use module or directory with limit and offset.",
            )

            module != null -> ToolResult.success(DiagnosticsScope.Module(module, arguments.related))

            directory != null -> ToolResult.success(DiagnosticsScope.Directory(directory))

            else -> ToolResult.success(DiagnosticsScope.Files(checkNotNull(files)))
        }
    }

    private fun window(arguments: DiagnosticsArguments): ToolResult<Pair<Int, Int>> {
        val limit = arguments.limit ?: DEFAULT_FILES
        val offset = arguments.offset ?: 0
        return when {
            limit !in 1..MAX_FILES -> invalid(
                "limit must be between 1 and $MAX_FILES files, was $limit.",
                "Omit it to analyse $DEFAULT_FILES files per call.",
            )

            offset < 0 -> invalid(
                "offset must not be negative, was $offset.",
                "Use 0 for the first files, then nextOffset.",
            )

            else -> ToolResult.success(offset to limit)
        }
    }

    private fun invalid(message: String, nextStep: String): ToolResult<Nothing> =
        ToolResult.failure(ErrorCode.INVALID_ARGUMENT, message, nextStep)

    private fun unsupported(): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.UNSUPPORTED,
        "This IDE has no batch code analysis that mc_diagnostics can use (the platform's VCS support, which " +
            "provides it, is not available).",
        "Use the official get_file_problems tool per file.",
    )

    companion object {
        /** Files analysed per call by default, and the hard bound. */
        const val DEFAULT_FILES: Int = 100
        const val MAX_FILES: Int = 500

        /**
         * Files per analysis batch: a timeout loses at most one batch of work. Measured on 2,000 generated files,
         * batches of 8 and of 32 analyse at the same rate (docs/notes-step7.md), so the smaller, finer one is used.
         */
        const val BATCH_SIZE: Int = 8

        /** Key of [DiagnosticsReport.byModule] for files outside any module. */
        const val NO_MODULE: String = ""

        val DEFAULT_TIMEOUT: Duration = 60.seconds
        val MAX_TIMEOUT: Duration = 600.seconds
    }
}
