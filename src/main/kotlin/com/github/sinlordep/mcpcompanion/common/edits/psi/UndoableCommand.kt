package com.github.sinlordep.mcpcompanion.common.edits.psi

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Runs a code change made for an MCP client as one IDE command that is marked global, so that one Edit | Undo reverts
 * all of it from anywhere in the IDE, and records which files it changed ([ChangedFilesRecorder]).
 *
 * Why a command of our own: the IDE's code changes (refactoring processors, quick fixes) mark their command global
 * only when no editor has the focus, which an MCP call cannot know; their own commands nest into this one. Call on
 * the EDT, outside any write action; [change] takes its write actions itself.
 */
class UndoableCommand(private val project: Project) {

    /** Runs [change] as the command [name] (user interface text, shown in Edit | Undo); returns the changed files. */
    fun run(name: String, change: () -> Unit): List<VirtualFile> = ChangedFilesRecorder(project).recording {
        val commands = CommandProcessor.getInstance()
        commands.executeCommand(
            project,
            {
                commands.markCurrentCommandAsGlobal(project)
                change()
            },
            name,
            null,
        )
    }
}
