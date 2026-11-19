package com.github.sinlordep.mcpcompanion.toolsets.structure

import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.intellij.mcpserver.McpToolset
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * `mc_file_structure` through the real gateway: the Structure view models of the IDE's languages, built without an
 * editor, and the JVM facts from UAST.
 */
class FileStructureToolsetTest : BasePlatformTestCase() {

    private lateinit var tools: ToolCalls

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<FileStructureToolset>().single()

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("shop/Shop.java", SHOP)
        myFixture.addFileToProject("conf/app.xml", "<app>\n  <server port=\"80\"/>\n  <client/>\n</app>\n")
        myFixture.addFileToProject("notes/readme.txt", "plain text\n")
        tools = ToolCalls(project)
    }

    override fun tearDown() {
        try {
            tools.dispose()
        } finally {
            super.tearDown()
        }
    }

    private fun structure(
        path: String,
        maxDepth: Int? = null,
        kinds: List<String>? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): FileStructureOutput = tools.call(TOOL) { toolset.fileStructure(path, maxDepth, kinds, limit, offset) }

    private fun error(path: String, maxDepth: Int? = null): String =
        tools.errorCode(TOOL) { toolset.fileStructure(path, maxDepth) }

    fun testAJavaFileListsItsClassesAndMembersWithKindsModifiersAndLines() {
        val output = structure("shop/Shop.java")
        assertEquals("shop/Shop.java", output.path)
        assertEquals("Java", output.language)
        val byName = output.elements.associateBy { it.id }
        val shop = byName.getValue("1")
        assertEquals("Shop", shop.name)
        assertEquals("class", shop.kind)
        assertEquals(listOf("public"), shop.modifiers)
        assertEquals(3, shop.line)
        assertEquals(3, shop.startLine)
        assertEquals(LAST_LINE, shop.endLine)
        assertEquals(
            listOf(
                "1.1 LIMIT field [private, static, final] 4",
                "1.2 items field [private] 5",
                "1.3 Shop constructor [public] 7",
                "1.4 buy method [public] 11",
                "1.5 Cart class [package-private, static, abstract] 15",
                "1.5.1 add method [package-private, abstract] 16",
                "2 Helper interface [package-private] 20",
            ),
            output.elements.drop(1).map { "${it.id} ${it.name} ${it.kind} ${it.modifiers.orEmpty()} ${it.line}" },
        )
        val buy = byName.getValue("1.4")
        assertEquals("buy(String, int): boolean", buy.signature)
        assertEquals(11, buy.startLine)
        assertEquals(13, buy.endLine)
        assertEquals(5, shop.children)
    }

    fun testDepthKindsAndPagingNarrowTheAnswer() {
        assertEquals(listOf("1", "2"), structure("shop/Shop.java", maxDepth = 1).elements.map { it.id })
        assertEquals(
            listOf("1.4", "1.5.1"),
            structure("shop/Shop.java", kinds = listOf("method")).elements.map {
                it.id
            },
        )
        val page = structure("shop/Shop.java", limit = 2, offset = 1)
        assertEquals(listOf("1.1", "1.2"), page.elements.map { it.id })
        assertTrue(page.hasMore)
        assertEquals(3, page.nextOffset)
        assertEquals(8, page.total)
    }

    fun testOtherLanguagesUseTheirOwnStructureView() {
        val xml = structure("conf/app.xml")
        assertEquals(listOf("app", "server", "client"), xml.elements.map { it.name })
        assertEquals(listOf(1, 2, 2), xml.elements.map { it.depth })
        assertEquals(2, xml.elements[1].line)
    }

    fun testFilesWithoutAStructureViewDirectoriesAndMissingFilesAreRefused() {
        assertEquals("UNSUPPORTED", error("notes/readme.txt"))
        assertEquals("INVALID_ARGUMENT", error("shop"))
        assertEquals("NOT_FOUND", error("shop/Missing.java"))
        assertEquals("INVALID_ARGUMENT", error("shop/Shop.java", maxDepth = 0))
    }

    private companion object {
        const val TOOL = "mc_file_structure"
        const val LAST_LINE = 18

        val SHOP = """
            package shop;

            public class Shop {
                private static final int LIMIT = 3;
                private java.util.List<String> items;

                public Shop() {
                    items = null;
                }

                public boolean buy(String item, int count) {
                    return count < LIMIT;
                }

                static abstract class Cart {
                    abstract void add(String item);
                }
            }

            interface Helper {}
        """.trimIndent() + "\n"
    }
}
