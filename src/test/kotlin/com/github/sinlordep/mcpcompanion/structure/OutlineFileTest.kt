package com.github.sinlordep.mcpcompanion.structure

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Flattening, filters and paging of `mc_file_structure` over a fake Structure view. */
class OutlineFileTest {

    private fun element(name: String, kind: String, vararg children: OutlineElement) =
        OutlineElement(name, kind, null, null, emptyList(), 1, 1, 1, children.toList())

    private val tree = listOf(
        element(
            "Shop",
            "class",
            element("items", "field"),
            element("Shop", "constructor"),
            element("buy", "method"),
            element("Cart", "class", element("add", "method")),
        ),
        element("Helper", "interface"),
    )

    private var requestedLimit = 0
    private var result: ToolResult<FileOutline> = ToolResult.success(FileOutline("src/Shop.java", "Java", tree, true))
    private val useCase = OutlineFile { _, maxElements ->
        requestedLimit = maxElements
        result
    }

    private fun outline(arguments: StructureArguments) = runBlocking { useCase.execute(arguments) }

    private fun entries(arguments: StructureArguments) =
        (outline(arguments) as ToolResult.Success).value.page.items.map { "${it.id} ${it.element.name}" }

    @Test
    fun `elements come in pre-order with ids that show the nesting`() {
        assertEquals(
            listOf("1 Shop", "1.1 items", "1.2 Shop", "1.3 buy", "1.4 Cart", "1.4.1 add", "2 Helper"),
            entries(StructureArguments("src/Shop.java")),
        )
        val value = (outline(StructureArguments("src/Shop.java")) as ToolResult.Success).value
        assertEquals(7, value.total)
        assertEquals(4, value.page.items.first().childCount)
        assertEquals(OutlineFile.MAX_ELEMENTS, requestedLimit)
    }

    @Test
    fun `maxDepth and kinds filter without changing the ids`() {
        assertEquals(listOf("1 Shop", "2 Helper"), entries(StructureArguments("a", maxDepth = 1)))
        assertEquals(
            listOf("1.3 buy", "1.4.1 add"),
            entries(StructureArguments("a", kinds = listOf(" Method "))),
        )
        assertEquals(listOf("1.3 buy"), entries(StructureArguments("a", maxDepth = 2, kinds = listOf("method"))))
    }

    @Test
    fun `pages continue with nextOffset`() {
        val first = (outline(StructureArguments("a", limit = 3)) as ToolResult.Success).value
        assertEquals(listOf("1", "1.1", "1.2"), first.page.items.map { it.id })
        assertTrue(first.page.hasMore)
        val last = (outline(StructureArguments("a", limit = 5, offset = first.page.nextOffset)) as ToolResult.Success)
        assertEquals(listOf("1.3", "1.4", "1.4.1", "2"), last.value.page.items.map { it.id })
        assertFalse(last.value.page.hasMore)
    }

    @Test
    fun `an empty file has no elements, a cut tree is reported incomplete`() {
        result = ToolResult.success(FileOutline("e.txt", "JSON", emptyList(), true))
        val empty = (outline(StructureArguments("e.txt")) as ToolResult.Success).value
        assertEquals(0, empty.total)
        assertTrue(empty.complete)
        result = ToolResult.success(FileOutline("big.json", "JSON", tree, false))
        assertFalse((outline(StructureArguments("big.json")) as ToolResult.Success).value.complete)
    }

    @Test
    fun `invalid arguments are refused and gateway failures pass through`() {
        fun code(arguments: StructureArguments) = (outline(arguments) as ToolResult.Failure).error.code
        assertEquals(ErrorCode.INVALID_ARGUMENT, code(StructureArguments(" ")))
        assertEquals(ErrorCode.INVALID_ARGUMENT, code(StructureArguments("a", maxDepth = 0)))
        assertEquals(ErrorCode.INVALID_ARGUMENT, code(StructureArguments("a", maxDepth = 51)))
        assertEquals(ErrorCode.INVALID_ARGUMENT, code(StructureArguments("a", kinds = listOf(" "))))
        assertEquals(ErrorCode.INVALID_ARGUMENT, code(StructureArguments("a", limit = 1001)))
        assertEquals(ErrorCode.INVALID_ARGUMENT, code(StructureArguments("a", offset = -1)))
        result = ToolResult.failure(ErrorCode.UNSUPPORTED, "no Structure view")
        assertEquals(ErrorCode.UNSUPPORTED, code(StructureArguments("a.txt")))
    }
}
