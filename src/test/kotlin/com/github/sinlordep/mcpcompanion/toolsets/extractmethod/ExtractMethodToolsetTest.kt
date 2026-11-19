package com.github.sinlordep.mcpcompanion.toolsets.extractmethod

import com.github.sinlordep.mcpcompanion.testing.RefactoringFixture
import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.github.sinlordep.mcpcompanion.toolsets.refactoring.RefactoringActionInput
import com.github.sinlordep.mcpcompanion.toolsets.refactoring.RefactoringOutput
import com.github.sinlordep.mcpcompanion.toolsets.refactoring.VisibilityInput
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.McpToolset
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** `mc_extract_method` on Java code (`ext/Calc.java` of the fixture), inside an MCP call context. */
class ExtractMethodToolsetTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<ExtractMethodToolset>().single()

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

    /** The lines [start] to [end] of `ext/Calc.java`, or the columns given. */
    private data class Code(
        val start: Int,
        val end: Int,
        val startColumn: Int? = null,
        val endColumn: Int? = null,
        val path: String = CALC,
    )

    @Suppress("LongParameterList")
    private fun call(
        code: Code,
        name: String = "compute",
        action: RefactoringActionInput = RefactoringActionInput.PREVIEW,
        visibility: VisibilityInput? = null,
        makeStatic: Boolean? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): RefactoringOutput = tools.call(TOOL) { run(code, name, action, visibility, makeStatic, limit, offset) }

    private fun error(
        code: Code,
        name: String = "compute",
        action: RefactoringActionInput = RefactoringActionInput.PREVIEW,
        makeStatic: Boolean? = null,
    ) = tools.errorCode(TOOL) { run(code, name, action, null, makeStatic, null, null) }

    @Suppress("LongParameterList")
    private suspend fun run(
        code: Code,
        name: String,
        action: RefactoringActionInput,
        visibility: VisibilityInput?,
        makeStatic: Boolean?,
        limit: Int?,
        offset: Int?,
    ) = toolset.extractMethod(
        code.path,
        code.start,
        code.end,
        name,
        action,
        startColumn = code.startColumn,
        endColumn = code.endColumn,
        visibility = visibility,
        makeStatic = makeStatic,
        limit = limit,
        offset = offset,
    )

    private fun text(path: String = CALC) =
        myFixture.findFileInTempDir(path).let { myFixture.psiManager.findFile(it)!!.text }

    fun testPreviewShowsTheNewSignatureAndTheExtractedCodeAndChangesNothing() {
        val output = call(TWO_STATEMENTS)
        assertTrue(output.conflicts.toString(), output.canApply)
        assertFalse(output.applied)
        assertEquals("private int compute(int a, int b), from 4:9-5:31", output.summary)
        assertEquals(CALC, output.range?.path)
        assertEquals(4, output.range?.startLine)
        assertNull("to the end of the line", output.range?.endColumn)
        assertEquals(listOf(CALC), output.files.map { it.path })
        assertEquals(1, output.fileCount)
        assertFalse(output.hasMore)
        assertEquals(RefactoringFixture.FILES.getValue(CALC), text())

        val beyond = call(TWO_STATEMENTS, limit = 1, offset = 1)
        assertEquals(emptyList<String>(), beyond.files.map { it.path })
        assertEquals(1, beyond.fileCount)
    }

    fun testApplyReplacesTheStatementsWithACallAndIsUndoable() {
        val output = call(TWO_STATEMENTS, action = RefactoringActionInput.APPLY, visibility = VisibilityInput.PUBLIC)
        assertTrue(output.applied)
        assertEquals(listOf(CALC), output.changedFiles)
        val text = text()
        assertTrue(text, "int doubled = compute(a, b);" in text)
        assertTrue(text, "public int compute(int a, int b) {" in text)

        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(null))
        val previous = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undo.undo(null)
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(RefactoringFixture.FILES.getValue(CALC), text())
    }

    fun testAnExpressionByColumns() {
        // `doubled + 1` in `return doubled + 1;`
        val output = call(Code(6, 6, startColumn = 16, endColumn = 27), action = RefactoringActionInput.APPLY)
        assertTrue(output.summary, output.summary!!.startsWith("private int compute(int doubled)"))
        assertTrue(text(), "return compute(doubled);" in text())
    }

    fun testStaticFollowsTheContextUnlessRequested() {
        assertTrue(call(Code(16, 16)).summary!!.startsWith("private static int compute(int v)"))
        assertFalse(call(TWO_STATEMENTS).summary!!.contains("static"))
        val forced = call(TWO_STATEMENTS, makeStatic = true)
        assertTrue(forced.conflicts.toString(), forced.canApply)
        assertTrue(forced.summary, forced.summary!!.startsWith("private static int compute(int a, int b)"))

        val instance = call(Code(16, 16), makeStatic = false)
        assertFalse(instance.canApply)
        assertTrue(instance.conflicts.toString(), instance.conflicts.single().message.contains("static context"))
    }

    fun testCodeWithSeveralExitsIsAConflictWithThePlaces() {
        val output = call(Code(10, 11))
        assertFalse(output.canApply)
        assertTrue(output.conflicts.toString(), output.conflicts.isNotEmpty())
        assertTrue(output.conflicts.toString(), output.conflicts.all { it.path == CALC && it.line != null })
        assertEquals("CONFLICT", error(Code(10, 11), action = RefactoringActionInput.APPLY))
        assertEquals(RefactoringFixture.FILES.getValue(CALC), text())
    }

    fun testAMethodOfThatSignatureIsAConflict() {
        // `sum * 2` would become helper(int sum), and Calc has helper(int v).
        val output = call(Code(5, 5, startColumn = 23, endColumn = 30), name = "helper")
        assertFalse(output.canApply)
        assertTrue(output.conflicts.toString(), output.conflicts.any { "helper" in it.message })
    }

    fun testRefusals() {
        assertEquals("INVALID_ARGUMENT", error(Code(4, 4, startColumn = 13, endColumn = 18)))
        assertEquals("INVALID_ARGUMENT", error(Code(40, 41)))
        assertEquals("INVALID_ARGUMENT", error(Code(5, 4)))
        assertEquals("INVALID_ARGUMENT", error(TWO_STATEMENTS, name = "class"))
        assertEquals("INVALID_ARGUMENT", error(TWO_STATEMENTS, name = " "))
        assertEquals("NOT_FOUND", error(Code(1, 1, path = "ext/Nope.java")))
        assertEquals("UNSUPPORTED", error(Code(9, 9, path = "kt/Client.kt")))
    }

    /** Through the server's own tool, with JSON arguments as a client sends them, and the JSON it returns. */
    fun testJsonArgumentsAsAClientSendsThem() {
        val arguments = buildJsonObject {
            put("path", CALC)
            put("startLine", 4)
            put("endLine", 5)
            put("methodName", "compute")
            put("visibility", "package_private")
        }
        val result = tools.callJson(TOOL, arguments)
        val text = result.content.filterIsInstance<McpToolCallResultContent.Text>().single().text
        val output = Json.parseToJsonElement(text).jsonObject
        assertTrue(text, output.getValue("canApply").jsonPrimitive.boolean)
        assertEquals(JsonPrimitive("int compute(int a, int b), from 4:9-5:31"), output["summary"])
        assertEquals(JsonPrimitive(4), output.getValue("range").jsonObject["startLine"])
        assertNull("no symbol for a code range", output["symbol"])
    }

    private companion object {
        const val TOOL = "mc_extract_method"
        const val CALC = "ext/Calc.java"

        /** `int sum = a + b;` and `int doubled = sum * 2;` in total(). */
        val TWO_STATEMENTS = Code(4, 5)
    }
}
