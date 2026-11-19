package com.github.sinlordep.mcpcompanion.quickfix.psi

import com.github.sinlordep.mcpcompanion.quickfix.AnalysedFix
import com.github.sinlordep.mcpcompanion.quickfix.FixHandle
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModChooseAction
import com.intellij.modcommand.ModCommandAction
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import kotlin.coroutines.cancellation.CancellationException

/**
 * What a fix needs to be applied later: the IDE's action ([action] null for a fix in the older form, which is never
 * run), where it applies, and the file content ([stamp], the document's modification stamp) it was computed for.
 */
internal class PsiFixHandle(val action: ModCommandAction?, val offset: Int, val file: VirtualFile, val stamp: Long) :
    FixHandle

/**
 * Turns the IDE's fix objects into [AnalysedFix]es the way Alt+Enter would show them at the problem: a `ModCommand`
 * fix is offered when its presentation is not null at the caret position of the problem, and its change is computed
 * (on a copy) to see whether it needs the user ([ModCommandChecks]); a fix that is a choice becomes one fix per
 * option. Fixes in the older form are listed with a refusal when they say they are available without an editor, or
 * cannot tell. A fix whose own code fails is left out (and logged). Call in a read action, off the EDT.
 */
internal class FixDescriptions(private val project: Project, private val checks: ModCommandChecks) {

    fun describe(candidate: FixCandidate, file: PsiFile, stamp: Long, position: String): List<AnalysedFix> =
        contained(candidate) {
            val action = candidate.action.asModCommandAction()
            if (action ==
                null
            ) {
                legacy(candidate, file, stamp, position)
            } else {
                modern(action, candidate, file, stamp, position)
            }
        }.orEmpty()

    fun contextAt(file: PsiFile, offset: Int): ActionContext =
        ActionContext(project, file, offset, TextRange(offset, offset), null)

    private fun modern(
        action: ModCommandAction,
        candidate: FixCandidate,
        file: PsiFile,
        stamp: Long,
        position: String,
    ): List<AnalysedFix> {
        val context = contextAt(file, candidate.offset)
        val name = action.getPresentation(context)?.name()?.let(::plain) ?: return emptyList()
        val command = action.perform(context)
        if (command !is ModChooseAction) {
            val handle = PsiFixHandle(action, candidate.offset, file.virtualFile, stamp)
            return listOf(AnalysedFix(position, name, checks.refusal(command), handle))
        }
        return command.actions().mapIndexedNotNull { index, option ->
            val optionName = option.getPresentation(context)?.name()?.let(::plain) ?: return@mapIndexedNotNull null
            val handle = PsiFixHandle(option, candidate.offset, file.virtualFile, stamp)
            AnalysedFix("$position.${index + 1}", "$name: $optionName", checks.refusal(option.perform(context)), handle)
        }
    }

    private fun legacy(candidate: FixCandidate, file: PsiFile, stamp: Long, position: String): List<AnalysedFix> {
        val action = candidate.action
        // Many such fixes need the editor even to answer (they fail without one): then they are listed as well.
        val available = contained(candidate) { action.isAvailable(project, null, file) } ?: true
        val name = plain(action.text.ifBlank { action.familyName })
        if (!available || name.isBlank()) return emptyList()
        return listOf(
            AnalysedFix(position, name, EDITOR_ONLY, PsiFixHandle(null, candidate.offset, file.virtualFile, stamp)),
        )
    }

    /** A fix's name as text: some names are HTML (e.g. "<html>Migrate 'x' type to 'String'</html>"). */
    private fun plain(name: String): String = StringUtil.removeHtmlTags(name, true).trim()

    /** Runs foreign fix code; a failure of it drops the fix instead of failing the call. */
    @Suppress("TooGenericExceptionCaught") // Any failure of a fix's own code must stay contained.
    private fun <T> contained(candidate: FixCandidate, block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        if (e is ControlFlowException || e is CancellationException || e is VirtualMachineError) throw e
        LOG.debug("Fix ${candidate.action.javaClass.name} failed without an editor; it is not listed", e)
        null
    }

    companion object {
        const val EDITOR_ONLY: String = "it works only in an open editor, where it may open a dialog, a popup or a " +
            "live template (for example to choose methods, a target package or names)"

        private val LOG = logger<FixDescriptions>()
    }
}
