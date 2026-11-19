package com.github.sinlordep.mcpcompanion.toolsets.pullup

import com.github.sinlordep.mcpcompanion.testing.RefactoringFixture
import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.github.sinlordep.mcpcompanion.toolsets.refactoring.RefactoringActionInput
import com.github.sinlordep.mcpcompanion.toolsets.refactoring.RefactoringOutput
import com.intellij.mcpserver.McpToolset
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** `mc_pull_up` on Java members used from Java and Kotlin, inside an MCP call context. */
class PullUpToolsetTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<PullUpToolset>().single()

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

    @Suppress("LongParameterList")
    private fun call(
        name: String?,
        action: RefactoringActionInput = RefactoringActionInput.PREVIEW,
        target: String? = null,
        with: List<String>? = null,
        makeAbstract: Boolean? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): RefactoringOutput = tools.call(TOOL) {
        toolset.pullUp(
            action,
            path = if (name == null) DOG else null,
            line = if (name == null) BARK_LINE else null,
            column = if (name == null) BARK_COLUMN else null,
            qualifiedName = name,
            targetClass = target,
            withMembers = with,
            makeAbstract = makeAbstract,
            limit = limit,
            offset = offset,
        )
    }

    private fun error(
        name: String? = null,
        target: String? = null,
        with: List<String>? = null,
        makeAbstract: Boolean? = null,
        action: RefactoringActionInput = RefactoringActionInput.PREVIEW,
        path: String? = null,
        line: Int? = null,
        column: Int? = null,
    ) = tools.errorCode(TOOL) {
        toolset.pullUp(
            action,
            path = path,
            line = line,
            column = column,
            qualifiedName = name,
            targetClass = target,
            withMembers = with,
            makeAbstract = makeAbstract,
        )
    }

    /** [error] for `bark()`, by position. */
    private fun barkError(target: String? = null, with: List<String>? = null) =
        error(null, target, with, path = DOG, line = BARK_LINE, column = BARK_COLUMN)

    private fun text(path: String) = myFixture.findFileInTempDir(path).let { myFixture.psiManager.findFile(it)!!.text }

    private fun snapshot() = RefactoringFixture.FILES.keys.associateWith(::text)

    private fun undo() {
        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(null))
        val previous = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undo.undo(null)
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    fun testPreviewOfAnInstanceMethodListsBothClassesAndChangesNothing() {
        val before = snapshot()
        val output = call(null)
        assertTrue(output.conflicts.toString(), output.canApply)
        assertFalse(output.applied)
        assertEquals("bark", output.symbol?.name)
        assertEquals(listOf("pull/Animal.java", "pull/Dog.java"), output.files.map { it.path })
        assertEquals("calls of an instance method stay as they are", 0, output.referenceCount)
        assertEquals(before, snapshot())
    }

    fun testApplyMovesTheMethodIntoTheSuperclassAndIsUndoable() {
        val output = call(null, RefactoringActionInput.APPLY)
        assertTrue(output.applied)
        assertEquals(listOf("pull/Animal.java", "pull/Dog.java"), output.changedFiles)
        assertTrue(text("pull/Animal.java"), "public int bark() { return 1; }" in text("pull/Animal.java"))
        assertFalse(text("pull/Dog.java"), "public int bark() {" in text("pull/Dog.java"))
        assertTrue("the overload stays", "public int bark(int times)" in text("pull/Dog.java"))

        undo()
        assertEquals(RefactoringFixture.FILES.getValue("pull/Dog.java"), text("pull/Dog.java"))
        assertEquals(RefactoringFixture.FILES.getValue("pull/Animal.java"), text("pull/Animal.java"))
    }

    fun testStaticMembersHaveTheirJavaReferencesRequalifiedAndArePaged() {
        val output = call("pull.Dog#MIN")
        assertTrue(output.conflicts.toString(), output.canApply)
        assertEquals(listOf("app/Kennel.java", "pull/Animal.java", "pull/Dog.java"), output.files.map { it.path })
        assertEquals(1, output.referenceCount)

        val page = call("pull.Dog#MIN", limit = 1, offset = 1)
        assertEquals(listOf("pull/Animal.java"), page.files.map { it.path })
        assertTrue(page.hasMore)
        assertEquals(2, page.nextOffset)

        val applied = call("pull.Dog#MIN", RefactoringActionInput.APPLY)
        assertEquals(listOf("app/Kennel.java", "pull/Animal.java", "pull/Dog.java"), applied.changedFiles)
        assertTrue(text("pull/Animal.java"), "MIN = 1" in text("pull/Animal.java"))
        assertTrue(text("app/Kennel.java"), "Animal.MIN" in text("app/Kennel.java"))
    }

    /** Kotlin's pull-up helper handles Kotlin classes only, and `Dog.MAX` in Kotlin would no longer compile. */
    fun testAKotlinReferenceToAStaticMemberIsAConflict() {
        val output = call("pull.Dog#MAX")
        assertFalse(output.canApply)
        val conflict = output.conflicts.single()
        assertEquals("kt/Walker.kt", conflict.path)
        assertTrue(conflict.message, "Kotlin reference to MAX" in conflict.message)
        assertEquals("CONFLICT", error("pull.Dog#MAX", action = RefactoringActionInput.APPLY))
    }

    /** Kotlin call sites of an instance method need no change: they call it through the subclass. */
    fun testKotlinCallsOfAPulledUpInstanceMethodStayAsTheyAre() {
        call(null, RefactoringActionInput.APPLY)
        assertEquals(RefactoringFixture.FILES.getValue("kt/Walker.kt"), text("kt/Walker.kt"))
        assertEquals(RefactoringFixture.FILES.getValue("app/Kennel.java"), text("app/Kennel.java"))
    }

    fun testAMemberThatUsesWhatStaysBehindIsAConflictUntilItMovesAlong() {
        val output = call("pull.Dog#older")
        assertFalse(output.canApply)
        assertTrue(output.conflicts.toString(), output.conflicts.any { "age" in it.message })
        val before = snapshot()
        assertEquals("CONFLICT", error("pull.Dog#older", action = RefactoringActionInput.APPLY))
        assertEquals(before, snapshot())

        val together = call("pull.Dog#older", with = listOf("age"))
        assertTrue(together.conflicts.toString(), together.canApply)
        val applied = call("pull.Dog#older", RefactoringActionInput.APPLY, with = listOf("age"))
        assertTrue(applied.applied)
        assertTrue(text("pull/Animal.java"), "int age;" in text("pull/Animal.java"))
        assertTrue(text("pull/Animal.java"), "older()" in text("pull/Animal.java"))
    }

    fun testAnAbstractMethodThatAnotherSubclassLacksIsAConflict() {
        val output = call(null, makeAbstract = true)
        assertFalse(output.canApply)
        assertTrue(output.conflicts.toString(), output.conflicts.any { "Cat" in it.message })
    }

    fun testIntoAnInterfaceAMethodBecomesADefaultOrAnAbstractMethod() {
        val asDefault = call(null, RefactoringActionInput.APPLY, target = "pull.Pet")
        assertTrue(asDefault.applied)
        assertTrue(text("pull/Pet.java"), "default int bark()" in text("pull/Pet.java"))
        undo()
        val asAbstract = call(null, RefactoringActionInput.APPLY, target = "Pet", makeAbstract = true)
        assertTrue(asAbstract.applied)
        assertTrue(text("pull/Pet.java"), "int bark();" in text("pull/Pet.java"))
        assertTrue("the body stays in the subclass", "return 1;" in text("pull/Dog.java"))
    }

    fun testAnInstanceFieldCannotGoIntoAnInterface() {
        val output = call("pull.Dog#age", target = "pull.Pet")
        assertFalse(output.canApply)
        assertTrue(output.conflicts.toString(), output.conflicts.isNotEmpty())
    }

    fun testRefusals() {
        assertEquals("NOT_FOUND", error("pull.Dog#nope"))
        assertEquals("NOT_FOUND", barkError(with = listOf("nothing")))
        assertEquals("AMBIGUOUS", error("pull.Dog#older", with = listOf("bark")))
        assertEquals("INVALID_ARGUMENT", barkError(target = "pull.Cat"))
        assertEquals("INVALID_ARGUMENT", barkError(target = "java.lang.Object"))
        assertEquals("INVALID_ARGUMENT", error("pull.Dog#age", makeAbstract = true))
        assertEquals("INVALID_ARGUMENT", barkError(with = listOf("")))
        assertEquals("UNSUPPORTED", error("pull.Dog"))
        assertEquals("UNSUPPORTED", error(path = "kt/Client.kt", line = 8, column = 9))
        assertEquals("INVALID_ARGUMENT", error("pull.Animal#legs"))
        assertEquals("INVALID_ARGUMENT", error())
    }

    private companion object {
        const val TOOL = "mc_pull_up"

        /** `bark()` in Dog.java, by position: Dog has an overload of it. */
        const val DOG = "pull/Dog.java"
        const val BARK_LINE = 5
        const val BARK_COLUMN = 16
    }
}
