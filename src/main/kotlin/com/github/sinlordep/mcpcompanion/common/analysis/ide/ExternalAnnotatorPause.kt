package com.github.sinlordep.mcpcompanion.common.analysis.ide

import com.intellij.lang.ExternalAnnotatorsFilter
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the IDE's external annotators away from the files a batch analysis without an editor (`mc_diagnostics`,
 * `mc_quick_fix`) analyses, while it analyses them.
 *
 * Why: the batch analysis creates an `ExternalToolPass` for every file that has an external annotator, which in
 * IntelliJ IDEA is every file (ESLint's annotator is registered for all languages). The pass hands its work to the
 * application-wide external-annotator queue and then polls until that work has run, for at most 60 s. In batches of
 * many files, and right after a sync, some passes polled the full 60 s while the queue was idle and empty (seen end
 * to end with thread dumps and the queue's state, docs/notes-step7.md): the call stalled for a minute per batch.
 *
 * The public `ExternalAnnotatorsFilter` extension point removes the annotators of a file before the pass is created
 * (the pass factory creates no pass when no external annotator applies), so nothing waits. Scoped to the files of a
 * running analysis and counted per file, so overlapping calls are safe. What it costs: findings of external
 * annotators are not reported (e.g. XML schema validation, Javadoc HTML lint, KDoc links, ESLint, Stylelint,
 * ShellCheck), and an editor highlighting such a file during those seconds also runs without them until the file is
 * highlighted again.
 */
@Service(Service.Level.APP)
class ExternalAnnotatorPause {

    private val paused = ConcurrentHashMap<VirtualFile, Int>()

    /** Runs [block] with external annotators paused for [files]. */
    suspend fun <T> during(files: Collection<VirtualFile>, block: suspend () -> T): T {
        files.forEach { file -> paused.merge(file, 1, Int::plus) }
        try {
            return block()
        } finally {
            files.forEach { file -> paused.computeIfPresent(file) { _, count -> (count - 1).takeIf { it > 0 } } }
        }
    }

    fun isPaused(file: VirtualFile): Boolean = paused.containsKey(file)

    companion object {
        fun getInstance(): ExternalAnnotatorPause = service()
    }
}

/** Registered in `plugin.xml` (`com.intellij.daemon.externalAnnotatorsFilter`); see [ExternalAnnotatorPause]. */
class PausedExternalAnnotatorsFilter : ExternalAnnotatorsFilter {
    override fun isProhibited(annotator: ExternalAnnotator<*, *>, file: PsiFile): Boolean {
        val virtualFile = file.viewProvider.virtualFile
        return ExternalAnnotatorPause.getInstance().isPaused(virtualFile)
    }
}
