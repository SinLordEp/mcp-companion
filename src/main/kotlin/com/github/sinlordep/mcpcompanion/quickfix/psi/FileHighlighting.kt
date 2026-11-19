package com.github.sinlordep.mcpcompanion.quickfix.psi

import com.github.sinlordep.mcpcompanion.common.analysis.ide.ExternalAnnotatorPause
import com.github.sinlordep.mcpcompanion.common.progress.ide.runUnderIndicator
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.MainPassesRunner
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The highlighting an editor shows for one file, computed without an editor: the IDE's main passes (the language's
 * error checks, annotators, the enabled inspections of the project profile) through `MainPassesRunner`, the runner
 * behind the platform's batch code analysis. Its `HighlightInfo`s carry the quick fixes the passes registered.
 *
 * `MainPassesRunner` and `HighlightInfo` sit in the platform's `impl` package but are not annotated internal or
 * experimental (checked member by member, and by the Plugin Verifier; docs/notes-step9.md). The runner restarts when
 * a write action begins and must run on a background thread without a read lock under a progress indicator, which
 * the coroutine's cancellation cancels. External annotators are paused for the file ([ExternalAnnotatorPause]), as for
 * `mc_diagnostics`: their pass can wait 60 s for an idle queue, and their findings rarely come with fixes.
 */
internal class FileHighlighting(private val project: Project) {

    /** The highlights of [file] at weak warning severity and above; unsaved editor changes are analysed too. */
    suspend fun highlights(file: VirtualFile): List<HighlightInfo> {
        withContext(Dispatchers.EDT) { PsiDocumentManager.getInstance(project).commitAllDocuments() }
        return ExternalAnnotatorPause.getInstance().during(listOf(file)) { run(file) }
    }

    private suspend fun run(file: VirtualFile): List<HighlightInfo> = runUnderIndicator {
        MainPassesRunner(project, TITLE, null)
            .runMainPasses(listOf(file), HighlightSeverity.WEAK_WARNING)
            .values.flatten()
    }

    private companion object {
        /** Only shown by the runner's modal progress on the EDT, which is never used here. */
        const val TITLE = "MCP Companion quick fixes"
    }
}
