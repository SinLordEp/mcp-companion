package com.github.sinlordep.mcpcompanion.toolsets.move

import com.github.sinlordep.mcpcompanion.testing.RefactoringFixture
import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.github.sinlordep.mcpcompanion.toolsets.refactoring.RefactoringActionInput
import com.github.sinlordep.mcpcompanion.toolsets.refactoring.RefactoringOutput
import com.intellij.mcpserver.McpToolset
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** `mc_move` on Java classes used from Java and Kotlin, inside an MCP call context. */
class MoveToolsetTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<MoveToolset>().single()

    override fun setUp() {
        super.setUp()
        RefactoringFixture.addTo(myFixture)
        tools = ToolCalls(project)
    }

    override fun tearDown() {
        try {
            tools.dispose()
        } finally {
            super.tearDown()
        }
    }

    private fun preview(name: String, target: String, limit: Int? = null, offset: Int? = null): RefactoringOutput =
        tools.call(TOOL) { toolset.move(target, qualifiedName = name, limit = limit, offset = offset) }

    private fun apply(name: String, target: String, root: String? = null): RefactoringOutput = tools.call(TOOL) {
        toolset.move(target, RefactoringActionInput.APPLY, qualifiedName = name, targetSourceRoot = root)
    }

    private fun error(name: String, target: String, root: String? = null, action: RefactoringActionInput) =
        tools.errorCode(TOOL) { toolset.move(target, action, qualifiedName = name, targetSourceRoot = root) }

    private fun classExists(name: String) =
        JavaPsiFacade.getInstance(project).findClass(name, GlobalSearchScope.projectScope(project)) != null

    private fun text(path: String) = myFixture.findFileInTempDir(path)?.let { myFixture.psiManager.findFile(it)!!.text }

    private fun snapshot() = RefactoringFixture.FILES.keys.associateWith(::text)

    fun testPreviewListsTheAffectedFilesInJavaAndKotlinAndChangesNothing() {
        val before = snapshot()
        val output = preview("model.Item", "catalog")
        assertTrue(output.canApply)
        assertFalse(output.applied)
        assertEquals(0, output.conflictCount)
        assertEquals("model.Item", output.symbol.qualifiedName)
        assertEquals(
            listOf("app/Shop.java", "kt/Client.kt", "model/Item.java"),
            output.files.map { it.path },
        )
        assertEquals(
            "the import and the parameter type",
            2,
            output.files.single {
                it.path == "app/Shop.java"
            }.references,
        )
        assertEquals(output.files.sumOf { it.references }, output.referenceCount)
        assertEquals(before, snapshot())
    }

    fun testPreviewPagesTheAffectedFiles() {
        val first = preview("model.Item", "catalog", limit = 2)
        assertEquals(3, first.fileCount)
        assertEquals(listOf("app/Shop.java", "kt/Client.kt"), first.files.map { it.path })
        assertTrue(first.hasMore)
        assertEquals(2, first.nextOffset)
        val second = preview("model.Item", "catalog", limit = 2, offset = 2)
        assertEquals(listOf("model/Item.java"), second.files.map { it.path })
        assertFalse(second.hasMore)
    }

    fun testApplyMovesTheClassUpdatesJavaAndKotlinReferencesAndIsUndoable() {
        val output = apply("model.Item", "catalog")
        assertTrue(output.applied)
        assertTrue(classExists("catalog.Item"))
        assertFalse(classExists("model.Item"))
        assertTrue(text("catalog/Item.java")!!.startsWith("package catalog;"))
        assertTrue(text("app/Shop.java")!!.contains("import catalog.Item;"))
        assertTrue(text("kt/Client.kt")!!.contains("import catalog.Item"))
        assertEquals(listOf("app/Shop.java", "catalog/Item.java", "kt/Client.kt"), output.changedFiles)

        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(null))
        val previous = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undo.undo(null)
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertTrue(classExists("model.Item"))
        assertEquals(RefactoringFixture.FILES.getValue("app/Shop.java"), text("app/Shop.java"))
    }

    fun testAConflictIsReportedByPreviewAndRefusedByApply() {
        val output = preview("model.Uses", "elsewhere")
        assertFalse(output.canApply)
        assertTrue(output.conflictCount >= 1)
        assertTrue(output.conflicts.toString(), output.conflicts.any { "Secret" in it.message })
        val before = snapshot()
        assertEquals("CONFLICT", error("model.Uses", "elsewhere", action = RefactoringActionInput.APPLY))
        assertEquals(before, snapshot())
        assertTrue(classExists("model.Uses"))
    }

    fun testRefusals() {
        val preview = RefactoringActionInput.PREVIEW
        assertEquals("CONFLICT", error("model.Item", "store", action = preview))
        assertEquals("NOT_FOUND", error("model.Nope", "catalog", action = preview))
        assertEquals("UNSUPPORTED", error("model.Item.Tag", "catalog", action = preview))
        assertEquals("UNSUPPORTED", error("kt.Thing", "catalog", action = preview))
        assertEquals("INVALID_ARGUMENT", error("model.Item", "model", action = preview))
        assertEquals("INVALID_ARGUMENT", error("model.Item", "not a package", action = preview))
        assertEquals("INVALID_ARGUMENT", error("model.Item", "catalog.class", action = preview))
        assertEquals("NOT_FOUND", error("model.Item", "catalog", root = "nowhere", action = preview))
        assertEquals("INVALID_ARGUMENT", error("model.Item", "catalog", root = "model", action = preview))
    }

    fun testIntoAnotherSourceRootKeepingThePackage() {
        val root = myFixture.tempDirFixture.findOrCreateDir("other")
        PsiTestUtil.addSourceRoot(module, root)
        try {
            val output = apply("model.Base", "model", root = "other")
            assertTrue(output.applied)
            val moved = JavaPsiFacade.getInstance(
                project,
            ).findClass("model.Base", GlobalSearchScope.projectScope(project))
            assertEquals(root, moved?.containingFile?.virtualFile?.parent?.parent)
            assertEquals("no reference changes in the same package", 0, output.referenceCount)
        } finally {
            PsiTestUtil.removeSourceRoot(module, root)
        }
    }

    private companion object {
        const val TOOL = "mc_move"
    }
}
