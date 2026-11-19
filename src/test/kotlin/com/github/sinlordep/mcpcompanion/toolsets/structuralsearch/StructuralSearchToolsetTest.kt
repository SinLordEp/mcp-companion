package com.github.sinlordep.mcpcompanion.toolsets.structuralsearch

import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.intellij.mcpserver.McpToolset
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** `mc_structural_search` through the real gateway: the IDE's Structural Search over Java files. */
class StructuralSearchToolsetTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<StructuralSearchToolset>().single()

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("accounts/Accounts.java", ACCOUNTS)
        myFixture.addFileToProject("other/Other.java", OTHER)
        tools = ToolCalls(project)
    }

    override fun tearDown() {
        try {
            tools.dispose()
        } finally {
            super.tearDown()
        }
    }

    private fun search(
        pattern: String? = null,
        template: String? = null,
        fileType: String? = null,
        scope: String? = null,
        constraints: List<VariableConstraintInput>? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): StructuralSearchOutput = tools.call(TOOL) {
        toolset.structuralSearch(
            pattern = pattern,
            template = template,
            fileType = fileType,
            scope = scope,
            constraints = constraints,
            limit = limit,
            offset = offset,
        )
    }

    private fun error(pattern: String? = null, template: String? = null, fileType: String? = null): String =
        tools.errorCode(TOOL) { toolset.structuralSearch(pattern = pattern, template = template, fileType = fileType) }

    private fun templates(query: String? = null, fileType: String? = null): StructuralSearchOutput = tools.call(TOOL) {
        toolset.structuralSearch(action = StructuralSearchActionInput.TEMPLATES, query = query, fileType = fileType)
    }

    fun testAnEqualsPatternFindsEveryEqualsCallWithItsVariables() {
        val output = search(EQUALS)
        assertEquals("Java", output.fileType)
        assertEquals(3, output.total)
        assertEquals(
            listOf("accounts/Accounts.java:11", "accounts/Accounts.java:20", "other/Other.java:5"),
            output.matches.orEmpty().map { "${it.path}:${it.line}" },
        )
        val first = output.matches.orEmpty().first()
        assertEquals("each.equals(name)", first.text)
        assertEquals("if (each.equals(name)) {", first.lineText)
        assertEquals(17, first.column)
        assertEquals(11, first.endLine)
        assertEquals("Accounts.contains", first.enclosing)
        assertEquals(
            mapOf("Instance" to listOf("each"), "Arg" to listOf("name")),
            first.variables.orEmpty().associate { it.name to it.values },
        )
    }

    fun testConstraintsNarrowByTextAndType() {
        val literal = search(EQUALS, constraints = listOf(VariableConstraintInput("Instance", text = "\".*\"")))
        assertEquals(listOf(20), literal.matches.orEmpty().map { it.line })
        val notLiteral = search(
            EQUALS,
            constraints = listOf(VariableConstraintInput("Instance", text = "\".*\"", textInverted = true)),
        )
        assertEquals(2, notLiteral.total)
        val integers = search(EQUALS, constraints = listOf(VariableConstraintInput("Instance", type = "Integer")))
        assertEquals(listOf("other/Other.java"), integers.matches.orEmpty().map { it.path })
    }

    fun testCountsAndTargets() {
        val anyArguments = search(
            "System.out.println(\$Args\$)",
            constraints = listOf(VariableConstraintInput("Args", minCount = 0, maxCount = -1)),
        )
        assertEquals(2, anyArguments.total)
        val oneArgument = search("System.out.println(\$Args\$)")
        assertEquals(1, oneArgument.total)
        val methods = search(
            "class \$Class\$ { \$ReturnType\$ \$Method\$(\$ParameterType\$ \$Parameter\$); }",
            constraints = listOf(
                VariableConstraintInput("Method", target = true, text = "is.*"),
                VariableConstraintInput("ParameterType", minCount = 0, maxCount = -1),
                VariableConstraintInput("Parameter", minCount = 0, maxCount = -1),
            ),
            scope = "file:accounts/Accounts.java",
        )
        assertEquals(listOf(18), methods.matches.orEmpty().map { it.line })
        assertEquals("isAdmin", methods.matches.orEmpty().single().text)
        assertEquals(
            "Parameter=[name], ParameterType=[String], ReturnType=[boolean]",
            methods.matches.orEmpty().single().variables.orEmpty().joinToString { "${it.name}=${it.values}" },
        )
        val catches = search("try { \$Body\$; } catch (\$Type\$ \$e\$) {}")
        // Variables nested in another variable's match are found; the matcher's own names (__catch_..) are not shown.
        assertEquals(
            "24 Body=[return Integer.parseInt(text);], e=[e], Type=[NumberFormatException]",
            catches.matches.orEmpty().joinToString { match ->
                "${match.line} " + match.variables.orEmpty().joinToString { "${it.name}=${it.values}" }
            },
        )
    }

    fun testScopesAndPaging() {
        assertEquals(1, search(EQUALS, scope = "dir:other").total)
        assertEquals(2, search(EQUALS, scope = "file:accounts/Accounts.java").total)
        val page = search(EQUALS, limit = 2, offset = 1)
        assertEquals(listOf(20, 5), page.matches.orEmpty().map { it.line })
        assertFalse(page.hasMore)
        val first = search(EQUALS, limit = 1)
        assertTrue(first.hasMore)
        assertEquals(1, first.nextOffset)
        assertEquals(
            "NOT_FOUND",
            tools.errorCode(TOOL) { toolset.structuralSearch(pattern = EQUALS, scope = "dir:none") },
        )
    }

    fun testNothingMatches() {
        for (pattern in listOf("\$a\$.hashCode()", "\$a\$.noMethodOfThisNameAnywhere()")) {
            val output = search(pattern)
            assertEquals(pattern, 0, output.total)
            assertEquals(emptyList<StructuralMatchOutput>(), output.matches)
            assertFalse(output.hasMore)
        }
    }

    fun testAKotlinPatternSearchesKotlinFiles() {
        myFixture.addFileToProject("kt/Hello.kt", "package kt\n\nfun main() {\n    println(\"hello\")\n}\n")
        val output = search("println(\$Arg\$)", fileType = "kotlin")
        assertEquals("Kotlin", output.fileType)
        assertEquals(listOf("kt/Hello.kt:4"), output.matches.orEmpty().map { "${it.path}:${it.line}" })
        assertEquals(listOf("\"hello\""), output.matches.orEmpty().single().variables.orEmpty().single().values)
    }

    fun testBadTemplatesLanguagesAndNamesAreRefused() {
        assertEquals("INVALID_ARGUMENT", error("class {"))
        assertEquals("UNSUPPORTED", error(EQUALS, fileType = "Cobol"))
        assertEquals("NOT_FOUND", error(template = "no such template anywhere"))
        assertEquals(
            "INVALID_ARGUMENT",
            tools.errorCode(TOOL) {
                toolset.structuralSearch(pattern = EQUALS, constraints = listOf(VariableConstraintInput("Missing")))
            },
        )
    }

    fun testTemplatesAreListedAndRunByName() {
        val all = templates(fileType = "Java")
        assertTrue(all.total > 10)
        assertTrue(all.templates.orEmpty().all { it.fileType == "Java" && it.predefined })
        val template = all.templates.orEmpty().first { "\$MethodCall\$" in it.pattern }
        assertEquals(
            "AMBIGUOUS",
            tools.errorCode(TOOL) { toolset.structuralSearch(template = "Method calls") },
        )
        val narrowed = templates(query = template.name, fileType = "Java")
        assertTrue(narrowed.templates.orEmpty().any { it.name == template.name })
        val calls = search(template = template.name, fileType = "Java", scope = "file:other/Other.java")
        assertEquals(template.name, calls.template)
        assertTrue(calls.total > 0)
    }

    private companion object {
        const val TOOL = "mc_structural_search"
        const val EQUALS = "\$Instance\$.equals(\$Arg\$)"

        val ACCOUNTS = """
            package accounts;

            import java.util.ArrayList;
            import java.util.List;

            public class Accounts {
                private final List<String> names = new ArrayList<>();

                public boolean contains(String name) {
                    for (String each : names) {
                        if (each.equals(name)) {
                            return true;
                        }
                    }
                    return false;
                }

                public boolean isAdmin(String name) {
                    // admin
                    return "admin".equals(name);
                }

                public int parse(String text) {
                    try {
                        return Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                    }
                    return -1;
                }

                public void log(Object value) {
                    System.out.println(value);
                    System.out.println();
                }
            }
        """.trimIndent() + "\n"

        val OTHER = """
            package other;

            class Other {
                boolean same(Integer a, Integer b) {
                    return a.equals(b);
                }
            }
        """.trimIndent() + "\n"
    }
}
