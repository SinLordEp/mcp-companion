package com.github.sinlordep.mcpcompanion.toolsets.safedelete

import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.intellij.mcpserver.McpToolset
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The fixed rules `mc_safe_delete` applies where the IDE's Safe Delete would ask the user (docs/notes-step2.md):
 * accessors, overriding and overridden methods, parameters, Kotlin properties, a class alone in its file.
 */
class SafeDeleteRulesTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<SafeDeleteToolset>().single()

    override fun setUp() {
        super.setUp()
        FILES.forEach { (path, text) -> myFixture.addFileToProject(path, text) }
        tools = ToolCalls(project)
    }

    override fun tearDown() {
        try {
            tools.dispose()
        } finally {
            super.tearDown()
        }
    }

    private fun preview(name: String) = tools.call(TOOL) { toolset.safeDelete(qualifiedName = name) }

    private fun apply(name: String) = tools.call(TOOL) {
        toolset.safeDelete(SafeDeleteActionInput.APPLY, qualifiedName = name)
    }

    private fun applyError(name: String) =
        tools.errorCode(TOOL) { toolset.safeDelete(SafeDeleteActionInput.APPLY, qualifiedName = name) }

    private fun text(path: String): String? =
        myFixture.findFileInTempDir(path)?.let { myFixture.psiManager.findFile(it)?.text }

    /**
     * (a) The getter reads the field and blocks; accessors are not deleted along. The setter's assignment is a
     * cleanup: the platform removes writes to a deleted field.
     */
    fun testFieldWithAccessorsIsBlockedByTheGetter() {
        val output = preview("rules.Box#size")
        assertEquals(listOf("Box.getSize"), output.blockers.map { it.enclosing })
        assertEquals(1, output.cleanups)
        assertEquals(setOf(BlockerOutput.KindOutput.REFERENCE), output.blockers.map { it.kind }.toSet())
        assertEquals("CONFLICT", applyError("rules.Box#size"))
        assertTrue(text(BOX)!!.contains("getSize"))
    }

    /** (b) Deleting only an implementation would break its class. */
    fun testMethodImplementingAnInterfaceMethodIsRefused() {
        assertEquals("CONFLICT", applyError("rules.Hello#greet"))
        assertTrue(text(HELLO)!!.contains("greet"))
    }

    /** (c) An unused interface method goes together with its unused implementation, as one undoable command. */
    fun testUnusedInterfaceMethodIsDeletedWithItsUnusedImplementation() {
        val output = apply("rules.Greeter#greet")
        assertTrue(output.deleted)
        assertEquals(listOf("rules/Hello.java"), output.alsoDeleted.map { it.path })
        assertFalse(text(GREETER)!!.contains("greet"))
        assertFalse(text(HELLO)!!.contains("greet"))
    }

    /** (c') An implementation that is called directly keeps the interface method alive. */
    fun testInterfaceMethodWhoseImplementationIsCalledIsBlocked() {
        val output = preview("rules.Named#name")
        assertEquals(listOf("Caller.call"), output.blockers.map { it.enclosing })
        assertEquals("CONFLICT", applyError("rules.Named#name"))
    }

    /** (d) A parameter would need a signature change at every call site. */
    fun testParameterIsUnsupported() {
        val parameter = BOX_TEXT.lines().indexOfFirst { "unused" in it }
        val column = BOX_TEXT.lines()[parameter].indexOf("unused") + 1
        assertEquals(
            "UNSUPPORTED",
            tools.errorCode(TOOL) { toolset.safeDelete(SafeDeleteActionInput.APPLY, BOX, parameter + 1, column) },
        )
    }

    /** (e) A Kotlin property is deleted with its accessors, which are part of it. */
    fun testUnusedKotlinPropertyIsDeleted() {
        assertTrue(apply("rules.Settings#retries").deleted)
        assertFalse(text(SETTINGS)!!.contains("retries"))
        assertTrue(text(SETTINGS)!!.contains("timeout"))
    }

    /** A class alone in its file is deleted with the file. */
    fun testClassAloneInItsFileIsDeletedWithTheFile() {
        assertTrue(apply("rules.Lonely").deleted)
        assertNull(text(LONELY))
    }

    private companion object {
        const val TOOL = "mc_safe_delete"
        const val BOX = "rules/Box.java"
        const val GREETER = "rules/Greeter.java"
        const val HELLO = "rules/Hello.java"
        const val SETTINGS = "rules/Settings.kt"
        const val LONELY = "rules/Lonely.java"
        val BOX_TEXT = """
            package rules;
            public class Box {
                private int size;
                public int getSize() { return size; }
                public void setSize(int s) { size = s; }
                public void touch(int unused) {}
            }
        """.trimIndent()
        val FILES = mapOf(
            BOX to BOX_TEXT,
            GREETER to "package rules;\npublic interface Greeter {\n    String greet();\n}",
            HELLO to "package rules;\npublic class Hello implements Greeter {\n" +
                "    public String greet() { return \"hi\"; }\n}",
            "rules/Named.java" to "package rules;\npublic interface Named {\n    String name();\n}",
            "rules/Thing.java" to
                "package rules;\npublic class Thing implements Named {\n    public String name() { return \"t\"; }\n}",
            "rules/Caller.java" to
                "package rules;\npublic class Caller {\n    String call() { return new Thing().name(); }\n}",
            SETTINGS to "package rules\n\nclass Settings {\n    val retries = 3\n    val timeout = 10\n}",
            LONELY to "package rules;\npublic class Lonely {}",
        )
    }
}
