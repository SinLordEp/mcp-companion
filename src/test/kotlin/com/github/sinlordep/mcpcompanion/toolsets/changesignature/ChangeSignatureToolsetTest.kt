package com.github.sinlordep.mcpcompanion.toolsets.changesignature

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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** `mc_change_signature` on Java methods called from Java and Kotlin, inside an MCP call context. */
class ChangeSignatureToolsetTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<ChangeSignatureToolset>().single()

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

    /** price(qty, unit) -> price(unit, qty, boolean rounded) with `false` at existing calls. */
    private val reorderAndAdd = listOf(
        ParameterInput(from = "unit"),
        ParameterInput(from = "0", name = "quantity"),
        ParameterInput(name = "rounded", type = "boolean", defaultValue = "false"),
    )

    private fun call(
        name: String,
        action: RefactoringActionInput = RefactoringActionInput.PREVIEW,
        parameters: List<ParameterInput>? = null,
        returnType: String? = null,
        visibility: VisibilityInput? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): RefactoringOutput = tools.call(TOOL) {
        toolset.changeSignature(
            action,
            qualifiedName = name,
            parameters = parameters,
            returnType = returnType,
            visibility = visibility,
            limit = limit,
            offset = offset,
        )
    }

    private fun error(name: String, parameters: List<ParameterInput>? = null, returnType: String? = null) =
        tools.errorCode(TOOL) {
            toolset.changeSignature(qualifiedName = name, parameters = parameters, returnType = returnType)
        }

    private fun text(path: String) = myFixture.findFileInTempDir(path).let { myFixture.psiManager.findFile(it)!!.text }

    private fun snapshot() = RefactoringFixture.FILES.keys.associateWith(::text)

    fun testPreviewCountsCallSitesInJavaAndKotlinAndChangesNothing() {
        val before = snapshot()
        val output = call("model.Item#price", parameters = reorderAndAdd)
        assertTrue(output.conflicts.toString(), output.canApply)
        assertEquals(listOf("app/Shop.java", "kt/Client.kt", "model/Item.java"), output.files.map { it.path })
        assertEquals(2, output.files.single { it.path == "app/Shop.java" }.references)
        assertEquals(1, output.files.single { it.path == "kt/Client.kt" }.references)
        assertEquals(before, snapshot())

        val page = call("model.Item#price", parameters = reorderAndAdd, limit = 1, offset = 1)
        assertEquals(listOf("kt/Client.kt"), page.files.map { it.path })
        assertTrue(page.hasMore)
        assertEquals(2, page.nextOffset)
    }

    fun testApplyRewritesTheDeclarationAndEveryCallAndIsUndoable() {
        val output = call("model.Item#price", RefactoringActionInput.APPLY, parameters = reorderAndAdd)
        assertTrue(output.applied)
        assertEquals(listOf("app/Shop.java", "kt/Client.kt", "model/Item.java"), output.changedFiles)
        assertTrue(
            text("model/Item.java"),
            "price(String unit, int quantity, boolean rounded)" in text("model/Item.java"),
        )
        assertTrue(
            text("app/Shop.java"),
            "i.price(\"kg\", 2, false) + i.price(\"g\", 3, false)" in text("app/Shop.java"),
        )
        assertTrue(text("kt/Client.kt"), "i.price(\"kg\", 1, false)" in text("kt/Client.kt"))

        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(null))
        val previous = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undo.undo(null)
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(RefactoringFixture.FILES.getValue("app/Shop.java"), text("app/Shop.java"))
        assertEquals(RefactoringFixture.FILES.getValue("model/Item.java"), text("model/Item.java"))
    }

    fun testTheTopmostMethodChangesWithItsOverriders() {
        val output = call(
            "model.Base#size",
            RefactoringActionInput.APPLY,
            parameters = listOf(ParameterInput(from = "a", type = "long")),
            returnType = "long",
        )
        assertTrue(output.applied)
        assertTrue(text("model/Sub.java"), "public long size(long a)" in text("model/Sub.java"))
        assertTrue(text("model/Base.java"), "public long size(long a)" in text("model/Base.java"))
    }

    fun testAnOverridingMethodIsAConflictThatNamesTheTopmostOne() {
        val output = call("model.Sub#size", parameters = listOf(ParameterInput(from = "a", type = "long")))
        assertFalse(output.canApply)
        val ours = output.conflicts.single { "overrides or implements model.Base#size" in it.message }
        assertEquals("model/Base.java", ours.path)
        assertEquals(
            "CONFLICT",
            tools.errorCode(TOOL) {
                toolset.changeSignature(
                    RefactoringActionInput.APPLY,
                    qualifiedName = "model.Sub#size",
                    parameters = listOf(ParameterInput(from = "a", type = "long")),
                )
            },
        )
        assertEquals(RefactoringFixture.FILES.getValue("model/Sub.java"), text("model/Sub.java"))
    }

    fun testWeakerVisibilityThatBreaksCallersIsAConflict() {
        val output = call("model.Item#price", visibility = VisibilityInput.PRIVATE)
        assertFalse(output.canApply)
        assertTrue(output.conflicts.toString(), output.conflicts.any { "Shop.total" in it.message })
    }

    fun testInvalidAndUnsupportedRequests() {
        assertEquals("INVALID_ARGUMENT", error("model.Item#price"))
        assertEquals("INVALID_ARGUMENT", error("model.Item#price", listOf(ParameterInput(name = "x", type = "int"))))
        assertEquals("INVALID_ARGUMENT", error("model.Item#price", returnType = "NoSuchType"))
        assertEquals("INVALID_ARGUMENT", error("model.Item#price", returnType = "int int"))
        assertEquals("INVALID_ARGUMENT", error("model.Item#price", listOf(ParameterInput(from = "nope"))))
        assertEquals("INVALID_ARGUMENT", error("model.Item#price", listOf(ParameterInput(from = "5"))))
        assertEquals(
            "INVALID_ARGUMENT",
            error("model.Item#price", listOf(ParameterInput(from = "qty"), ParameterInput(from = "0"))),
        )
        assertEquals("INVALID_ARGUMENT", error("model.Item#price", returnType = "int"))
        assertEquals("NOT_FOUND", error("model.Item#nope", returnType = "long"))
        val kotlin = myFixture.findFileInTempDir("kt/Client.kt")
        assertNotNull(kotlin)
        val line = RefactoringFixture.FILES.getValue("kt/Client.kt").lines().indexOfFirst { "fun buy" in it } + 1
        assertEquals(
            "UNSUPPORTED",
            tools.errorCode(TOOL) {
                toolset.changeSignature(path = "kt/Client.kt", line = line, column = 5, returnType = "Int")
            },
        )
    }

    /** Through the server's own tool, with JSON arguments as a client sends them (also a numeric `from`). */
    fun testJsonArgumentsAsAClientSendsThem() {
        val arguments = buildJsonObject {
            put("qualifiedName", "model.Item#price")
            put(
                "parameters",
                buildJsonArray {
                    add(buildJsonObject { put("from", "unit") })
                    add(buildJsonObject { put("from", "0") })
                },
            )
        }
        val result = tools.callJson(TOOL, arguments)
        val text = result.content.filterIsInstance<McpToolCallResultContent.Text>().single().text
        val output = Json.parseToJsonElement(text).jsonObject
        assertTrue(text, output.getValue("canApply").jsonPrimitive.boolean)
        assertEquals(JsonPrimitive(false), output["applied"])
    }

    private companion object {
        const val TOOL = "mc_change_signature"
    }
}
