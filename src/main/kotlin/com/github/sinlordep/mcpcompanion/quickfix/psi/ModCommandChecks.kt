package com.github.sinlordep.mcpcompanion.quickfix.psi

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.symbols.FileOrigin
import com.intellij.modcommand.ModChooseAction
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCopyToClipboard
import com.intellij.modcommand.ModCreateFile
import com.intellij.modcommand.ModDeleteFile
import com.intellij.modcommand.ModDisplayMessage
import com.intellij.modcommand.ModEditOptions
import com.intellij.modcommand.ModHighlight
import com.intellij.modcommand.ModMoveFile
import com.intellij.modcommand.ModNavigate
import com.intellij.modcommand.ModNothing
import com.intellij.modcommand.ModOpenUrl
import com.intellij.modcommand.ModRegisterTabOut
import com.intellij.modcommand.ModShowConflicts
import com.intellij.modcommand.ModStartRename
import com.intellij.modcommand.ModStartTemplate
import com.intellij.modcommand.ModUpdateFileText
import com.intellij.openapi.vfs.VirtualFile

/**
 * Whether a fix's computed change (its `ModCommand`, a description of what it would do, made without changing
 * anything) can be carried out without the user. A `ModCommand` is a tree of primitive steps; every step must be
 * one of these:
 *
 * - a text change of a writable project file, or the deletion of one;
 * - what an editor would only show or offer afterwards (move the caret, highlight, start an in-place rename, Tab
 *   targets, an optional template): skipped by the IDE's batch execution, which this tool uses.
 *
 * A fix without any text change or deletion is refused as well: it only shows something (e.g. "Find cause" of a data
 * flow warning navigates to the code that explains it), and applying it would report success without a change.
 *
 * Every other step needs the user or is not a code change, so the fix is refused with the reason: a choice (handled by
 * the caller, which lists the options as fixes of their own), a live template to fill in, a dialog for options or
 * conflicts, an error message, a clipboard or browser action, a new or moved file. Steps of a kind this list does not
 * know are refused too, so a new kind in a later IDE never runs unchecked. The experimental step kinds of 263
 * (`ModUpdateReferences`, `ModLaunchEditorAction`, `ModUpdateSystemOptions`) are not referenced and so fall under
 * that rule.
 */
internal class ModCommandChecks(private val files: ProjectFiles) {

    /** Why [command] cannot be applied without the user; null when it can. Call in a read action. */
    fun refusal(command: ModCommand): String? {
        if (command.isEmpty) return "it changes nothing here"
        val steps = command.unpack()
        return steps.firstNotNullOfOrNull(::stepRefusal)
            ?: NO_CODE_CHANGE.takeIf { steps.none { it is ModUpdateFileText || it is ModDeleteFile } }
    }

    /** The text changes and deletions of [command]: the files it changes. */
    fun changedFiles(command: ModCommand): List<VirtualFile> = command.unpack().mapNotNull { step ->
        when (step) {
            is ModUpdateFileText -> step.file()
            is ModDeleteFile -> step.file()
            else -> null
        }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per kind of step; the list is the specification.
    private fun stepRefusal(step: ModCommand): String? = when (step) {
        is ModUpdateFileText -> fileRefusal(step.file())

        is ModDeleteFile -> fileRefusal(step.file())

        is ModNavigate, is ModHighlight, is ModNothing, is ModStartRename, is ModRegisterTabOut -> null

        is ModStartTemplate -> if (step.optional()) null else "it asks for input in a live template in the editor"

        is ModChooseAction -> "it asks the user to choose (${step.title()})"

        is ModShowConflicts -> "the IDE reports conflicts and asks whether to go on"

        is ModEditOptions<*> -> "it opens a dialog to edit options (${step.title()})"

        is ModDisplayMessage ->
            if (step.kind() ==
                ModDisplayMessage.MessageKind.ERROR
            ) {
                "the IDE refuses it: ${step.messageText()}"
            } else {
                null
            }

        is ModCopyToClipboard -> "it only copies text to the clipboard"

        is ModOpenUrl -> "it only opens a web page"

        is ModCreateFile, is ModMoveFile -> "it creates or moves files, which this tool does not do"

        else -> "it does something this tool cannot check (${step.javaClass.simpleName})"
    }

    private companion object {
        const val NO_CODE_CHANGE = "it changes no code; it only navigates or shows something in the editor"
    }

    private fun fileRefusal(file: VirtualFile): String? = when {
        !file.isValid -> "it would change a file that no longer exists"

        files.originOf(file) != FileOrigin.PROJECT ->
            "it would change ${files.pathOf(file)}, which is not one of the project's own files"

        !file.isWritable -> "it would change ${files.pathOf(file)}, which is read-only"

        else -> null
    }
}
