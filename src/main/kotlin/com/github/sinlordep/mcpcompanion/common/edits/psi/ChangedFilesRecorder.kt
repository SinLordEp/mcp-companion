package com.github.sinlordep.mcpcompanion.common.edits.psi

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent

/**
 * Which files a code change (a refactoring, a quick fix) actually changed: every file whose PSI changed, was added or
 * was moved while [recording] ran. Also covers changes the IDE makes along with it (e.g. imports optimised by a
 * refactoring helper).
 * Directories are not files and are left out. Call on the EDT.
 */
class ChangedFilesRecorder(private val project: Project) {

    /** Runs [action] and returns the changed files that still exist, in the order of their first change. */
    fun recording(action: () -> Unit): List<VirtualFile> {
        val changed = LinkedHashSet<VirtualFile>()
        val lifetime = Disposer.newDisposable("mc code changes")
        try {
            PsiManager.getInstance(project).addPsiTreeChangeListener(Listener(changed), lifetime)
            action()
        } finally {
            Disposer.dispose(lifetime)
        }
        return changed.filter { it.isValid && !it.isDirectory }
    }

    private class Listener(private val changed: MutableSet<VirtualFile>) : PsiTreeChangeAdapter() {
        private fun note(event: PsiTreeChangeEvent) {
            event.file?.virtualFile?.let(changed::add)
            (event.child as? PsiFile)?.virtualFile?.let(changed::add)
            (event.element as? PsiFile)?.virtualFile?.let(changed::add)
        }

        override fun childAdded(event: PsiTreeChangeEvent) = note(event)

        override fun childRemoved(event: PsiTreeChangeEvent) = note(event)

        override fun childReplaced(event: PsiTreeChangeEvent) = note(event)

        override fun childMoved(event: PsiTreeChangeEvent) = note(event)

        override fun childrenChanged(event: PsiTreeChangeEvent) = note(event)

        override fun propertyChanged(event: PsiTreeChangeEvent) = note(event)
    }
}
