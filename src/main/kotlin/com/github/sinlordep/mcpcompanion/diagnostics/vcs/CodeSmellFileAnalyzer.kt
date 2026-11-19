package com.github.sinlordep.mcpcompanion.diagnostics.vcs

import com.github.sinlordep.mcpcompanion.common.analysis.ide.ExternalAnnotatorPause
import com.github.sinlordep.mcpcompanion.common.build.ProblemSeverity
import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.diagnostics.Diagnostic
import com.github.sinlordep.mcpcompanion.diagnostics.FileAnalysis
import com.github.sinlordep.mcpcompanion.diagnostics.FileAnalyzer
import com.github.sinlordep.mcpcompanion.diagnostics.SourceFile
import com.github.sinlordep.mcpcompanion.diagnostics.ide.FileAnalyzerProvider
import com.intellij.codeInsight.CodeSmellInfo
import com.intellij.codeInsight.daemon.ProblemHighlightFilter
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vcs.CodeSmellDetector
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Registered in `mcp-companion-vcs.xml`. */
class CodeSmellFileAnalyzerProvider : FileAnalyzerProvider {
    override fun analyzerFor(project: Project): FileAnalyzer = CodeSmellFileAnalyzer(project)
}

/**
 * The IDE's analysis of whole files without an editor, through the public `CodeSmellDetector` (the platform's
 * "Analyze code before commit"). It runs the highlighting passes an editor runs (the language's error checks,
 * annotators and the enabled inspections of the project's inspection profile, or the profile chosen for commit
 * checks), at warning severity and above: errors, warnings, and other severities ranked between or above them; weak
 * warnings and information are not reported. The detector's own ignore list drops failed-test markers.
 *
 * External annotators are paused for the analysed files ([ExternalAnnotatorPause]): their pass made whole batches
 * wait 60 s.
 *
 * Threading: the detector blocks; it runs on a background thread under a progress indicator of ours, which the
 * coroutine's cancellation cancels. It must not run under a read lock. Documents are committed on the EDT first, so
 * that unsaved edits are analysed as the editor shows them.
 */
internal class CodeSmellFileAnalyzer(private val project: Project) : FileAnalyzer {

    override suspend fun analyze(files: List<SourceFile>): FileAnalyzer.Batch {
        val paths = ProjectFiles.of(project)
        val (eligible, skipped) = readAction { classify(files, paths) }
        if (eligible.isEmpty()) return FileAnalyzer.Batch.Done(skipped)
        withContext(Dispatchers.EDT) { PsiDocumentManager.getInstance(project).commitAllDocuments() }
        val dumb = DumbService.getInstance(project)
        val indexing = dumb.modificationTracker.modificationCount
        val smells = ExternalAnnotatorPause.getInstance().during(eligible.keys) { detect(eligible.keys.toList()) }
        val interrupted = dumb.isDumb || dumb.modificationTracker.modificationCount != indexing
        return if (interrupted) {
            FileAnalyzer.Batch.Interrupted
        } else {
            FileAnalyzer.Batch.Done(resultsOf(eligible, smells) + skipped)
        }
    }

    private suspend fun resultsOf(
        eligible: Map<VirtualFile, SourceFile>,
        smells: List<CodeSmellInfo>,
    ): List<FileAnalysis> {
        val byFile = readAction {
            val documents = FileDocumentManager.getInstance()
            smells.groupBy { documents.getFile(it.document) }
        }
        return eligible.map { (file, source) ->
            FileAnalysis.Analysed(source, byFile[file].orEmpty().map(::diagnosticOf).sortedWith(ORDER))
        }
    }

    private fun classify(
        files: List<SourceFile>,
        paths: ProjectFiles,
    ): Pair<Map<VirtualFile, SourceFile>, List<FileAnalysis.Skipped>> {
        val eligible = LinkedHashMap<VirtualFile, SourceFile>()
        val skipped = ArrayList<FileAnalysis.Skipped>()
        val psi = PsiManager.getInstance(project)
        for (source in files) {
            ProgressManager.checkCanceled()
            val file = paths.find(source.path)
            val psiFile = file?.takeIf { it.isValid }?.let(psi::findFile)
            when {
                file == null || psiFile == null -> skipped += FileAnalysis.Skipped(source, NOT_SOURCE)

                !ProblemHighlightFilter.shouldProcessFileInBatch(psiFile) ->
                    skipped += FileAnalysis.Skipped(source, EXCLUDED)

                else -> eligible[file] = source
            }
        }
        return eligible to skipped
    }

    /** Runs the blocking detector on a background thread; cancelling the caller cancels its indicator. */
    private suspend fun detect(files: List<VirtualFile>): List<CodeSmellInfo> = coroutineScope {
        val indicator = ProgressIndicatorBase()
        val canceller = launch {
            try {
                awaitCancellation()
            } finally {
                indicator.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                ProgressManager.getInstance().runProcess(
                    Computable { CodeSmellDetector.getInstance(project).findCodeSmells(files) },
                    indicator,
                )
            }
        } finally {
            canceller.cancel()
        }
    }

    private fun diagnosticOf(info: CodeSmellInfo): Diagnostic {
        val description = info.description.orEmpty()
        val inspection = INSPECTION.matchEntire(description)
        return Diagnostic(
            severity = if (info.severity >= HighlightSeverity.ERROR) ProblemSeverity.ERROR else ProblemSeverity.WARNING,
            message = inspection?.groupValues?.get(2) ?: description,
            line = info.startLine + 1,
            column = info.startColumn + 1,
            inspection = inspection?.groupValues?.get(1),
        )
    }

    private companion object {
        /** The detector writes an inspection's findings as `[InspectionId] description`. */
        val INSPECTION = Regex("^\\[([A-Za-z0-9_.$-]+)] (.*)$", RegexOption.DOT_MATCHES_ALL)

        val ORDER: Comparator<Diagnostic> = compareBy({ it.line }, { it.column }, { it.severity }, { it.message })

        const val NOT_SOURCE = "not a file the IDE can analyse"
        const val EXCLUDED = "the IDE excludes this file from batch analysis (e.g. it is outside the source roots)"
    }
}
