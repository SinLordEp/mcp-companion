package com.github.sinlordep.mcpcompanion.common.paging

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTest {

    private fun request(limit: Int?, offset: Int?): PageRequest =
        (PageRequest.of(limit, offset) as ToolResult.Success).value

    @Test
    fun `defaults are the first hundred items`() {
        assertEquals(request(null, null), PageRequest.FIRST)
        assertEquals(100, PageRequest.FIRST.limit)
        assertEquals(0, PageRequest.FIRST.offset)
    }

    @Test
    fun `invalid limit and offset are argument errors`() {
        for ((limit, offset) in listOf(0 to 0, -1 to 0, PageRequest.MAX_LIMIT + 1 to 0, 10 to -1)) {
            val result = PageRequest.of(limit, offset)
            assertEquals("$limit/$offset", ErrorCode.INVALID_ARGUMENT, (result as ToolResult.Failure).error.code)
        }
    }

    @Test
    fun `first page of a longer list has more`() {
        val page = (1..250).asSequence().page(PageRequest.FIRST)
        assertEquals((1..100).toList(), page.items)
        assertTrue(page.hasMore)
        assertEquals(100, page.nextOffset)
    }

    @Test
    fun `last page has no next offset`() {
        val page = (1..250).asSequence().page(request(100, 200))
        assertEquals((201..250).toList(), page.items)
        assertFalse(page.hasMore)
        assertNull(page.nextOffset)
    }

    @Test
    fun `exactly full page has no more`() {
        val page = (1..10).asSequence().page(request(10, 0))
        assertEquals(10, page.items.size)
        assertFalse(page.hasMore)
    }

    @Test
    fun `offset past the end gives an empty last page`() {
        val page = (1..3).asSequence().page(request(10, 50))
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test
    fun `consumes only one element beyond the page`() {
        var produced = 0
        generateSequence(1) { it + 1 }.onEach { produced++ }.page(request(5, 10))
        assertEquals(16, produced)
    }
}
