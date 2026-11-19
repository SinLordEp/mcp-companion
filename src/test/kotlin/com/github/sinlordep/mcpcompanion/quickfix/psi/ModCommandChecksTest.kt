package com.github.sinlordep.mcpcompanion.quickfix.psi

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModNavigate
import com.intellij.modcommand.ModUpdateFileText
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Which computed changes [ModCommandChecks] lets through, built from primitive steps. */
class ModCommandChecksTest : BasePlatformTestCase() {

    private val checks get() = ModCommandChecks(ProjectFiles.of(project))

    private fun file() = myFixture.addFileToProject("a/Code.java", "package a;\n\nclass Code {}\n").virtualFile

    private fun rename(file: VirtualFile) =
        ModUpdateFileText(file, "package a;\n\nclass Code {}\n", "package a;\n\nclass Changed {}\n", emptyList())

    fun testATextChangeOfAProjectFileIsApplied() {
        val file = file()
        val update = rename(file)
        assertNull(checks.refusal(update))
        assertEquals(listOf(file), checks.changedFiles(update))
    }

    fun testAFixThatOnlyNavigatesIsRefused() {
        val navigate = ModNavigate(file(), 0, 0, 0)
        assertEquals("it changes no code; it only navigates or shows something in the editor", checks.refusal(navigate))
    }

    fun testATextChangeFollowedByNavigationIsApplied() {
        val file = file()
        val update = rename(file)
        assertNull(checks.refusal(update.andThen(ModNavigate(file, 0, 0, 0))))
    }

    fun testAnEmptyCommandChangesNothing() {
        assertEquals("it changes nothing here", checks.refusal(ModCommand.nop()))
    }
}
