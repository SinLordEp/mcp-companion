package com.github.sinlordep.mcpcompanion.toolsets.safedelete

import com.github.sinlordep.mcpcompanion.testing.NavigationFixture
import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.intellij.mcpserver.McpToolset
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** `mc_safe_delete` end to end, inside an MCP call context; the deletion runs on the EDT as the platform requires. */
class SafeDeleteToolsetTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<SafeDeleteToolset>().single()

    override fun setUp() {
        super.setUp()
        NavigationFixture.addTo(myFixture)
        tools = ToolCalls(project)
    }

    override fun tearDown() {
        try {
            tools.dispose()
        } finally {
            super.tearDown()
        }
    }

    private fun preview(name: String, limit: Int? = null, offset: Int? = null) = tools.call(TOOL) {
        toolset.safeDelete(SafeDeleteActionInput.PREVIEW, qualifiedName = name, limit = limit, offset = offset)
    }

    private fun apply(name: String) = tools.call(TOOL) {
        toolset.safeDelete(SafeDeleteActionInput.APPLY, qualifiedName = name)
    }

    private fun applyError(name: String) =
        tools.errorCode(TOOL) { toolset.safeDelete(SafeDeleteActionInput.APPLY, qualifiedName = name) }

    private fun classExists(name: String) =
        JavaPsiFacade.getInstance(project).findClass(name, GlobalSearchScope.projectScope(project)) != null

    private fun text(path: String) = myFixture.findFileInTempDir(path).let { myFixture.psiManager.findFile(it)!!.text }

    fun testPreviewListsBlockingReferencesAndCountsImportsAsCleanups() {
        val output = preview("shapes.Circle")
        assertFalse(output.canDelete)
        assertFalse(output.deleted)
        assertEquals(1, output.blockerCount)
        val blocker = output.blockers.single()
        assertEquals(BlockerOutput.KindOutput.REFERENCE, blocker.kind)
        assertEquals("app/Main.java", blocker.path)
        assertEquals("Shape s = new Circle(2);", blocker.lineText)
        assertEquals("Main.total", blocker.enclosing)
        assertEquals("the import goes with the class", 1, output.cleanups)
    }

    fun testApplyRefusesWhileBlockersExistAndChangesNothing() {
        val before = text("shapes/Circle.java")
        assertEquals("CONFLICT", applyError("shapes.Circle"))
        assertTrue(classExists("shapes.Circle"))
        assertEquals(before, text("shapes/Circle.java"))
    }

    fun testOverriddenMethodListsItsOverridersAsAlsoDeletedAndIsBlockedByCalls() {
        val output = preview("shapes.Shape#area", limit = 1)
        assertFalse(output.canDelete)
        assertEquals(2, output.blockerCount)
        assertEquals(BlockerOutput.KindOutput.REFERENCE, output.blockers.single().kind)
        assertTrue(output.hasMore)
        assertEquals(1, output.nextOffset)
        assertEquals(1, preview("shapes.Shape#area", offset = 1).blockers.size)
        assertEquals(
            "overriders in Circle, Square, RoundedSquare and the anonymous class",
            setOf("shapes/Circle.java", "shapes/Square.java", "shapes/RoundedSquare.java", "app/Main.java"),
            output.alsoDeleted.map { it.path }.toSet(),
        )
        assertEquals("CONFLICT", applyError("shapes.Shape#area"))
    }

    fun testAMethodThatOverridesAnotherIsRefused() {
        val output = preview("shapes.Circle#area")
        assertFalse(output.canDelete)
        // Ours, at the overridden method; the Java delegate may report its own conflict as well.
        val ours = output.blockers.filter { "It overrides or implements Shape.area" in it.message.orEmpty() }
        assertEquals(output.blockers.toString(), listOf("shapes/Shape.java"), ours.map { it.path })
        assertEquals(BlockerOutput.KindOutput.CONFLICT, ours.single().kind)
        assertEquals("CONFLICT", applyError("shapes.Circle#area"))
    }

    fun testUnusedClassIsDeletedAndTheDeletionIsUndoable() {
        assertTrue(preview("shapes.Unused").canDelete)
        val output = apply("shapes.Unused")
        assertTrue(output.deleted)
        assertEquals("shapes.Unused", output.symbol.qualifiedName)
        assertFalse(classExists("shapes.Unused"))

        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(null))
        // Deleting a file is a global action; the IDE asks before undoing it.
        val previous = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undo.undo(null)
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        assertTrue(classExists("shapes.Unused"))
    }

    fun testUnusedMethodIsDeletedInOneUndoableCommand() {
        myFixture.configureFromTempProjectFile("shapes/Circle.java")
        val before = myFixture.editor.document.text
        val output = apply("shapes.Circle#grow")
        assertTrue(output.deleted)
        assertFalse("grow" in text("shapes/Circle.java"))

        val editor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(editor))
        undo.undo(editor)
        assertEquals(before, myFixture.editor.document.text)
        FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
    }

    fun testPositionInputAndErrors() {
        val grow = NavigationFixture.at("shapes/Circle.java", "grow")
        val output = tools.call(TOOL) { toolset.safeDelete(path = grow.path, line = grow.line, column = grow.column) }
        assertTrue("preview is the default action", output.canDelete && !output.deleted)
        assertTrue("grow" in text("shapes/Circle.java"))

        assertEquals("NOT_FOUND", applyError("shapes.Nope"))
        assertEquals("AMBIGUOUS", applyError("shapes.Square#scale"))
        assertEquals("INVALID_ARGUMENT", tools.errorCode(TOOL) { toolset.safeDelete(SafeDeleteActionInput.APPLY) })
    }

    private companion object {
        const val TOOL = "mc_safe_delete"
    }
}
