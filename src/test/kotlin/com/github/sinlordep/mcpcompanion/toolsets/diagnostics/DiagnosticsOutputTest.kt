package com.github.sinlordep.mcpcompanion.toolsets.diagnostics

import com.github.sinlordep.mcpcompanion.common.build.ProblemSeverity
import com.github.sinlordep.mcpcompanion.common.modulegraph.GraphDirection
import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.diagnostics.Diagnostic
import com.github.sinlordep.mcpcompanion.diagnostics.DiagnosticsReport
import com.github.sinlordep.mcpcompanion.diagnostics.DiagnosticsScope
import com.github.sinlordep.mcpcompanion.diagnostics.FileAnalysis
import com.github.sinlordep.mcpcompanion.diagnostics.PartialReason
import com.github.sinlordep.mcpcompanion.diagnostics.SourceFile
import com.github.sinlordep.mcpcompanion.diagnostics.Totals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The mapping of a diagnostics report to MCP output: scope text, totals, problems, the per-file bound, paging. */
class DiagnosticsOutputTest {

    @Test
    fun `report fields map one to one, problems are bounded per file`() {
        val file = SourceFile("app/src/A.java", "app.main")
        val problems = (1..60).map { Diagnostic(ProblemSeverity.ERROR, "e$it", it, 3) } +
            Diagnostic(ProblemSeverity.WARNING, "unused", 70, 1, "unused")
        val report = DiagnosticsReport(
            scope = DiagnosticsScope.Module("app.main", GraphDirection.DEPENDENCIES),
            modules = listOf("lib.main", "app.main"),
            minSeverity = ProblemSeverity.WARNING,
            totalFiles = 40,
            analysed = 20,
            page = Page(listOf(FileAnalysis.Analysed(file, problems)), 0, hasMore = true, nextOffset = 20),
            partial = PartialReason.TIMEOUT,
            skipped = listOf(FileAnalysis.Skipped(SourceFile("app/x.bin", "app.main"), "binary")),
            totals = Totals(20, 60, 1),
            byModule = mapOf("app.main" to Totals(20, 60, 1)),
        )
        val output = DiagnosticsOutput.from(report)
        assertEquals("module app.main with its dependencies", output.scope)
        assertEquals(listOf("app.main", "lib.main"), output.modules)
        assertEquals("warning", output.minSeverity)
        assertEquals(60, output.errors)
        assertEquals(1, output.warnings)
        assertEquals(listOf(ModuleTotalsOutput("app.main", 20, 60, 1)), output.byModule)
        val listed = output.files.single()
        assertEquals(50, listed.problems.size)
        assertEquals(11, listed.omittedProblems)
        assertEquals(ProblemOutput("error", 1, 3, "e1"), listed.problems.first())
        assertEquals(listOf(SkippedFileOutput("app/x.bin", "binary")), output.notAnalysed)
        assertEquals("timeout", output.partial)
        assertEquals(20, output.nextOffset)
        assertEquals(true, output.hasMore)
    }
}
