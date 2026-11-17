package com.github.sinlordep.mcpcompanion.diagnostics

import com.github.sinlordep.mcpcompanion.common.build.ProblemSeverity
import com.github.sinlordep.mcpcompanion.common.modulegraph.Dependency
import com.github.sinlordep.mcpcompanion.common.modulegraph.DependencyScope
import com.github.sinlordep.mcpcompanion.common.modulegraph.DependencyTarget
import com.github.sinlordep.mcpcompanion.common.modulegraph.GraphDirection
import com.github.sinlordep.mcpcompanion.common.modulegraph.ProjectGraph
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.testing.ManualClock
import com.github.sinlordep.mcpcompanion.testing.settleUntil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Scope, window, severity floor, totals, partial results; fake files and analyzer, no IDE, no real time. */
class CollectDiagnosticsTest {

    private val clock = ManualClock()
    private val environment = FakeDiagnosticsEnvironment()
    private val diagnostics = CollectDiagnostics(environment, clock)

    private fun call(arguments: DiagnosticsArguments) = runBlocking { diagnostics.execute(arguments) }

    private fun report(arguments: DiagnosticsArguments) = call(arguments).value()

    private fun error(line: Int) = Diagnostic(ProblemSeverity.ERROR, "cannot find symbol", line, 5)

    private fun warning(line: Int) = Diagnostic(ProblemSeverity.WARNING, "unused", line, 1, "unused")

    @Test
    fun `module scope lists the module's files, include adds dependencies or dependents`() {
        environment.module("app", "app/A.java").module("lib", "lib/L.java").module("core", "core/C.java")
            .dependsOn("app", "lib").dependsOn("lib", "core")
        assertEquals(listOf("lib/L.java"), report(DiagnosticsArguments(module = "lib")).analysedPaths())
        assertEquals(
            listOf("core/C.java", "lib/L.java"),
            report(DiagnosticsArguments(module = "lib", related = GraphDirection.DEPENDENCIES)).analysedPaths(),
        )
        val dependents = report(DiagnosticsArguments(module = "lib", related = GraphDirection.DEPENDENTS))
        assertEquals(listOf("app", "lib"), dependents.modules.sorted())
        assertEquals(ErrorCode.NOT_FOUND, call(DiagnosticsArguments(module = "LIB")).code())
    }

    @Test
    fun `errors only by default, warnings on request, totals always count both`() {
        environment.module("m", "m/A.java", "m/B.java", "m/C.java")
        environment.problems["m/A.java"] = listOf(error(3), warning(7))
        environment.problems["m/B.java"] = listOf(warning(2))
        val errorsOnly = report(DiagnosticsArguments(module = "m"))
        assertEquals(listOf("m/A.java"), errorsOnly.page.items.map { it.file.path })
        assertEquals(listOf(error(3)), errorsOnly.page.items.single().problems)
        assertEquals(Totals(files = 3, errors = 1, warnings = 2), errorsOnly.totals)
        assertEquals(mapOf("m" to Totals(3, 1, 2)), errorsOnly.byModule)

        val withWarnings = report(DiagnosticsArguments(module = "m", minSeverity = ProblemSeverity.WARNING))
        assertEquals(listOf("m/A.java", "m/B.java"), withWarnings.page.items.map { it.file.path })
        assertEquals(2, withWarnings.page.items[0].problems.size)
    }

    @Test
    fun `a call analyses limit files from offset and says where to continue`() {
        environment.directory("src", (1..5).map { "src/F$it.java" })
        val first = report(DiagnosticsArguments(directory = "src", limit = 2))
        assertEquals(5, first.totalFiles)
        assertEquals(2, first.analysed)
        assertTrue(first.page.hasMore)
        assertEquals(2, first.page.nextOffset)
        val last = report(DiagnosticsArguments(directory = "src", limit = 2, offset = 4))
        assertEquals(1, last.analysed)
        assertFalse(last.page.hasMore)
        assertNull(last.page.nextOffset)
        assertNull(last.partial)
    }

    @Test
    fun `the timeout returns what was analysed, partial, with the next offset at the first file not analysed`() =
        runBlocking {
            environment.directory("src", (1..45).map { "src/F%02d.java".format(it) })
            environment.analyzer.holdBatch = 2
            val call = async { diagnostics.execute(DiagnosticsArguments(directory = "src", timeoutSeconds = 5)) }
            settleUntil("the second batch runs") { environment.analyzer.batches == 2 && clock.sleeping == 1 }
            clock.advance(5.seconds)
            val report = call.await().value()
            assertEquals(PartialReason.TIMEOUT, report.partial)
            assertEquals(CollectDiagnostics.BATCH_SIZE, report.analysed)
            assertEquals(CollectDiagnostics.BATCH_SIZE, report.page.nextOffset)
            assertTrue(report.page.hasMore)
        }

    @Test
    fun `a timeout while the IDE indexes says indexing`() = runBlocking {
        environment.directory("src", listOf("src/A.java"))
        environment.indexes = CompletableDeferred()
        val call = async { diagnostics.execute(DiagnosticsArguments(directory = "src", timeoutSeconds = 3)) }
        settleUntil("the call waits") { clock.sleeping == 1 }
        clock.advance(3.seconds)
        val report = call.await().value()
        assertEquals(PartialReason.INDEXING, report.partial)
        assertEquals(0, report.analysed)
        assertEquals(0, report.page.nextOffset)
    }

    @Test
    fun `a batch that indexing interrupted is analysed again`() {
        environment.module("m", "m/A.java")
        environment.problems["m/A.java"] = listOf(error(1))
        environment.analyzer.interruptions = 1
        val report = report(DiagnosticsArguments(module = "m"))
        assertEquals(2, environment.analyzer.batches)
        assertEquals(1, report.totals.errors)
    }

    @Test
    fun `skipped files are reported, not counted as analysed clean files`() {
        environment.module("m", "m/A.java", "m/data.bin")
        environment.skip["m/data.bin"] = "not a file the IDE can analyse"
        val report = report(DiagnosticsArguments(module = "m"))
        assertEquals(listOf("m/data.bin"), report.skipped.map { it.file.path })
        assertEquals(1, report.totals.files)
        assertEquals(2, report.analysed)
    }

    @Test
    fun `invalid arguments`() {
        environment.module("m", "m/A.java")
        assertEquals(ErrorCode.INVALID_ARGUMENT, call(DiagnosticsArguments()).code())
        assertEquals(ErrorCode.INVALID_ARGUMENT, call(DiagnosticsArguments(module = "m", directory = "m")).code())
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            call(DiagnosticsArguments(directory = "m", related = GraphDirection.DEPENDENTS)).code(),
        )
        assertEquals(ErrorCode.INVALID_ARGUMENT, call(DiagnosticsArguments(module = "m", limit = 501)).code())
        assertEquals(ErrorCode.INVALID_ARGUMENT, call(DiagnosticsArguments(module = "m", offset = -1)).code())
        assertEquals(ErrorCode.INVALID_ARGUMENT, call(DiagnosticsArguments(module = "m", timeoutSeconds = 601)).code())
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            call(DiagnosticsArguments(files = (0..500).map { "f$it.java" })).code(),
        )
        environment.analyzerAvailable = false
        assertEquals(ErrorCode.UNSUPPORTED, call(DiagnosticsArguments(module = "m")).code())
    }

    private fun DiagnosticsReport.analysedPaths() = environment.analyzer.seen.toList().also {
        environment.analyzer.seen.clear()
    }.sorted().also { assertEquals(it.size, analysed) }
}

/** Modules, directories and files by path; the analyzer returns [problems] per path. */
class FakeDiagnosticsEnvironment : DiagnosticsEnvironment {
    private val moduleFiles = linkedMapOf<String, List<String>>()
    private val directories = mutableMapOf<String, List<String>>()
    private val dependencies = mutableListOf<Dependency>()
    val problems = mutableMapOf<String, List<Diagnostic>>()
    val skip = mutableMapOf<String, String>()
    var indexes: CompletableDeferred<Unit>? = null
    var analyzerAvailable = true
    val analyzer = FakeAnalyzer()

    fun module(name: String, vararg files: String) = apply { moduleFiles[name] = files.toList() }

    fun directory(path: String, files: List<String>) = apply { directories[path] = files }

    fun dependsOn(from: String, to: String) = apply {
        dependencies += Dependency(from, DependencyTarget.Module(to), DependencyScope.COMPILE, false)
    }

    override suspend fun moduleGraph() = ProjectGraph(moduleFiles.keys.toSet(), dependencies.toList())

    override suspend fun filesOf(selection: FileSelection): ToolResult<List<SourceFile>> {
        val files = when (selection) {
            is FileSelection.Modules -> selection.names.flatMap { name ->
                moduleFiles[name].orEmpty().map { SourceFile(it, name) }
            }

            is FileSelection.Directory -> directories[selection.path].orEmpty().map { SourceFile(it, "m") }

            is FileSelection.Files -> selection.paths.map { SourceFile(it, null) }
        }
        return ToolResult.success(files.sortedBy { it.path })
    }

    override fun analyzer(): FileAnalyzer? = analyzer.takeIf { analyzerAvailable }

    override suspend fun awaitIndexes() {
        indexes?.await()
    }

    inner class FakeAnalyzer : FileAnalyzer {
        var batches = 0
        var interruptions = 0

        /** The batch with this number never ends (to let a timeout pass during it). */
        var holdBatch: Int? = null
        val seen = mutableListOf<String>()

        override suspend fun analyze(files: List<SourceFile>): FileAnalyzer.Batch {
            batches++
            if (batches == holdBatch) CompletableDeferred<Unit>().await()
            if (interruptions > 0) {
                interruptions--
                return FileAnalyzer.Batch.Interrupted
            }
            seen += files.map { it.path }
            return FileAnalyzer.Batch.Done(
                files.map { file ->
                    skip[file.path]?.let { FileAnalysis.Skipped(file, it) }
                        ?: FileAnalysis.Analysed(file, problems[file.path].orEmpty())
                },
            )
        }
    }
}

fun <T> ToolResult<T>.value(): T =
    (this as? ToolResult.Success)?.value ?: throw AssertionError("expected success: $this")

fun ToolResult<*>.code() = (this as? ToolResult.Failure)?.error?.code ?: throw AssertionError("expected failure: $this")
