package com.github.sinlordep.mcpcompanion.toolsets.quickfix

import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.intellij.mcpserver.McpToolset
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * `mc_quick_fix` through the real gateway: the editor's highlighting of a file that is not open, its registered and
 * deferred fixes, `ModCommand` checks and the batch execution. The light test project has no JDK, so the few
 * `java.lang` classes the Java checks need are project sources.
 */
class QuickFixToolsetTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<QuickFixToolset>().single()

    override fun setUp() {
        super.setUp()
        JAVA_LANG.forEach { (name, text) -> myFixture.addFileToProject("java/lang/$name.java", text) }
        myFixture.addFileToProject("a/Helper.java", "package a;\n\npublic class Helper {}\n")
        myFixture.addFileToProject("a/Single.java", "package a;\n\npublic class Single {}\n")
        myFixture.addFileToProject("c/Helper.java", "package c;\n\npublic class Helper {}\n")
        myFixture.addFileToProject("a/Shape.java", "package a;\n\npublic interface Shape {\n    int area();\n}\n")
        myFixture.addFileToProject("a/Boom.java", "package a;\n\npublic class Boom extends Exception {}\n")
        myFixture.addFileToProject("b/User.java", USER)
        myFixture.addFileToProject("b/Clean.java", "package b;\n\nclass Clean {\n}\n")
        tools = ToolCalls(project)
    }

    override fun tearDown() {
        try {
            tools.dispose()
        } finally {
            super.tearDown()
        }
    }

    private fun list(
        path: String = USER_PATH,
        line: Int? = null,
        endLine: Int? = null,
        limit: Int? = null,
        offset: Int? = null,
        minSeverity: QuickFixSeverityInput? = null,
    ): QuickFixOutput = tools.call(TOOL) {
        toolset.quickFix(
            path = path,
            line = line,
            endLine = endLine,
            limit = limit,
            offset = offset,
            minSeverity = minSeverity,
        )
    }

    private fun apply(fix: String, path: String = USER_PATH): QuickFixOutput =
        tools.call(TOOL) { toolset.quickFix(QuickFixActionInput.APPLY, path = path, fix = fix) }

    private fun applyError(fix: String?, path: String? = USER_PATH): String =
        tools.errorCode(TOOL) { toolset.quickFix(QuickFixActionInput.APPLY, path = path, fix = fix) }

    private fun text(path: String = USER_PATH): String =
        myFixture.psiManager.findFile(myFixture.findFileInTempDir(path)!!)!!.text

    private fun QuickFixOutput.problemAt(line: Int): ProblemOutput = problems.single { it.line == line }

    private fun ProblemOutput.fix(name: String): FixOutput =
        fixes.orEmpty().singleOrNull { it.name == name } ?: throw AssertionError("no fix '$name' in $fixes")

    fun testListsEachProblemWithTheFixesAltEnterOffers() {
        val output = list()
        assertEquals("b/User.java", output.path)
        val abstract = output.problems.single { "must either be declared abstract" in it.message }
        assertEquals("error", abstract.severity)
        assertEquals(USER_LINE_CLASS, abstract.line)
        assertTrue(abstract.fix("Make 'User' abstract").applicable)
        val implement = abstract.fix("Implement methods")
        assertFalse(implement.applicable)
        assertTrue(implement.reason!!, "only in an open editor" in implement.reason!!)

        val throwing = output.problemAt(USER_LINE_THROW)
        assertEquals("Unhandled exception: a.Boom", throwing.message)
        assertTrue(throwing.fix("Add exception to method signature").applicable)
        assertTrue(throwing.fix("Surround with try/catch").applicable)
        assertEquals("throw new Boom();", throwing.lineText.substringAfter("{ ").substringBefore(" }"))
        assertTrue(throwing.fixes!!.all { Regex("$USER_LINE_THROW:\\d+/\\d+(\\.\\d+)?-[0-9a-f]{8}").matches(it.id) })
    }

    fun testAnImportWithSeveralCandidatesIsListedOncePerClass() {
        val helper = list(line = USER_LINE_HELPER).problems.single { it.message == "Cannot resolve symbol 'Helper'" }
        val imports = helper.fixes!!.filter { it.name.startsWith("Import class") }
        assertEquals(imports.toString(), 2, imports.size)
        assertTrue(
            imports.toString(),
            imports.any { it.name.endsWith("a.Helper") } && imports.any { it.name.endsWith("c.Helper") },
        )
        assertTrue(imports.all { it.applicable && it.id.substringAfter('/').substringBefore('-').contains('.') })

        val choice = imports.single { it.name.endsWith("c.Helper") }
        val applied = apply(choice.id)
        assertEquals(listOf("b/User.java"), applied.changedFiles)
        assertEquals(choice.name, applied.applied!!.name)
        assertTrue(text().contains("import c.Helper;"))
    }

    fun testAppliesASingleImportAddsThrowsAndMakesTheClassAbstract() {
        val single = list().problems.single { it.message == "Cannot resolve symbol 'Single'" }
        val import = single.fixes!!.single { it.name.startsWith("Import class") }
        assertTrue(import.applicable)
        apply(import.id)
        assertTrue(text().contains("import a.Single;"))

        val throwing = list().problems.single { it.message == "Unhandled exception: a.Boom" }
        apply(throwing.fix("Add exception to method signature").id)
        assertTrue(text(), text().contains("void risky() throws Boom {"))

        val abstract = list().problems.single { "must either be declared abstract" in it.message }
        apply(abstract.fix("Make 'User' abstract").id)
        assertTrue(text(), text().contains("abstract class User implements Shape"))
    }

    fun testAnAppliedFixIsOneUndoableCommand() {
        val before = text()
        val throwing = list().problems.single { it.message == "Unhandled exception: a.Boom" }
        apply(throwing.fix("Surround with try/catch").id)
        assertTrue(text(), text().contains("catch (Boom e)"))
        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(null))
        val previous = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            undo.undo(null)
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(before, text())
    }

    fun testAFixThatNeedsTheUserIsRefusedAndChangesNothing() {
        val before = text()
        val implement = list().problems.single {
            "must either be declared abstract" in it.message
        }.fix("Implement methods")
        assertEquals("UNSUPPORTED", applyError(implement.id))
        assertEquals(before, text())
    }

    fun testAnIdFromBeforeAnEditIsStale() {
        val add = list().problems.single {
            it.message == "Unhandled exception: a.Boom"
        }.fix("Add exception to method signature")
        val document = PsiDocumentManager.getInstance(
            project,
        ).getDocument(myFixture.psiManager.findFile(myFixture.findFileInTempDir(USER_PATH)!!)!!)!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "// edited\n") }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val edited = text()
        assertEquals("CONFLICT", applyError(add.id))
        assertEquals(edited, text())
        // The same fix, listed again, has a new id (its line moved) and applies.
        val again = list().problems.single {
            it.message == "Unhandled exception: a.Boom"
        }.fix("Add exception to method signature")
        assertFalse(again.id == add.id)
        apply(again.id)
        assertTrue(text().contains("throws Boom"))
    }

    fun testIdsAreReproducedByAnotherAnalysisOfTheSameContent() {
        val first = list().problems.flatMap { it.fixes.orEmpty().map(FixOutput::id) }
        val second = list().problems.flatMap { it.fixes.orEmpty().map(FixOutput::id) }
        assertTrue(first.isNotEmpty())
        assertEquals(first, second)
    }

    fun testLinesSeverityAndPaging() {
        val all = list()
        assertTrue(all.problemCount!! >= 5)
        val onLine = list(line = USER_LINE_THROW)
        assertEquals(listOf(USER_LINE_THROW), onLine.problems.map { it.line })
        val range = list(line = USER_LINE_HELPER, endLine = USER_LINE_THROW)
        assertTrue(range.problems.all { it.line in USER_LINE_HELPER..USER_LINE_THROW })

        val errors = list(minSeverity = QuickFixSeverityInput.ERROR)
        assertTrue(errors.problems.all { it.severity == "error" })

        val first = list(limit = 1)
        assertEquals(1, first.problems.size)
        assertTrue(first.hasMore)
        assertEquals(1, first.nextOffset)
        val second = list(limit = 1, offset = 1)
        assertEquals(all.problems[1].message, second.problems.single().message)
    }

    fun testACleanFileHasNoProblems() {
        val output = list(path = "b/Clean.java")
        assertEquals(0, output.problemCount)
        assertTrue(output.problems.isEmpty())
        assertFalse(output.hasMore)
    }

    fun testRefusals() {
        assertEquals("NOT_FOUND", tools.errorCode(TOOL) { toolset.quickFix(path = "b/Nope.java") })
        assertEquals("INVALID_ARGUMENT", tools.errorCode(TOOL) { toolset.quickFix() })
        val outside = File.createTempFile("Outside", ".java").apply {
            deleteOnExit()
            writeText("class Outside {}\n")
        }
        assertEquals("INVALID_ARGUMENT", tools.errorCode(TOOL) { toolset.quickFix(path = outside.path) })
        assertEquals("INVALID_ARGUMENT", tools.errorCode(TOOL) { toolset.quickFix(path = USER_PATH, endLine = 3) })
        assertEquals(
            "INVALID_ARGUMENT",
            tools.errorCode(TOOL) {
                toolset.quickFix(path = USER_PATH, line = 5, endLine = 2)
            },
        )
        assertEquals("INVALID_ARGUMENT", tools.errorCode(TOOL) { toolset.quickFix(path = USER_PATH, limit = 101) })
        assertEquals("INVALID_ARGUMENT", applyError(null))
        assertEquals("INVALID_ARGUMENT", applyError("not-an-id"))
        assertEquals("CONFLICT", applyError("99:1/1-00000000"))
        assertEquals(
            "INVALID_ARGUMENT",
            tools.errorCode(TOOL) {
                toolset.quickFix(path = USER_PATH, fix = "1:1/1-00000000")
            },
        )
        assertEquals(
            "INVALID_ARGUMENT",
            tools.errorCode(TOOL) {
                toolset.quickFix(QuickFixActionInput.APPLY, path = USER_PATH, fix = "1:1/1-00000000", line = 2)
            },
        )
    }

    private companion object {
        const val TOOL = "mc_quick_fix"
        const val USER_PATH = "b/User.java"
        const val USER_LINE_CLASS = 6
        const val USER_LINE_HELPER = 7
        const val USER_LINE_THROW = 9

        val USER = """
            package b;

            import a.Shape;
            import a.Boom;

            class User implements Shape {
                Helper helper;
                Single single;
                void risky() { throw new Boom(); }
                int count = true;
            }
        """.trimIndent() + "\n"

        val JAVA_LANG = mapOf(
            "Object" to "package java.lang; public class Object {}",
            "String" to "package java.lang; public final class String {}",
            "Throwable" to "package java.lang; public class Throwable {}",
            "Exception" to "package java.lang; public class Exception extends Throwable {}",
            "RuntimeException" to "package java.lang; public class RuntimeException extends Exception {}",
            "Error" to "package java.lang; public class Error extends Throwable {}",
        )
    }
}
