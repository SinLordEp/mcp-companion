package com.github.sinlordep.mcpcompanion.run.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleBufferTest {

    private fun ConsoleBuffer.texts(query: ConsoleQuery = ConsoleQuery(100)) = read(query).lines.map { it.text }

    @Test
    fun `chunks are assembled into lines and CRLF ends a line`() {
        val buffer = ConsoleBuffer()
        buffer.append("he", ConsoleStream.STDOUT)
        buffer.append("llo\r", ConsoleStream.STDOUT)
        buffer.append("\nwor", ConsoleStream.STDOUT)
        assertEquals(listOf("hello"), buffer.texts())
        buffer.append("ld\n", ConsoleStream.STDOUT)
        assertEquals(listOf("hello", "world"), buffer.texts())
        assertEquals(listOf(1L, 2L), buffer.read(ConsoleQuery(100)).lines.map { it.seq })
    }

    @Test
    fun `a lone carriage return overwrites the line like a terminal`() {
        val buffer = ConsoleBuffer()
        buffer.append("10%\r50%\r100%\n", ConsoleStream.STDOUT)
        assertEquals(listOf("100%"), buffer.texts())
    }

    @Test
    fun `ANSI sequences are removed, also when split across chunks`() {
        val buffer = ConsoleBuffer()
        buffer.append("\u001B[3", ConsoleStream.STDOUT)
        buffer.append("1mred\u001B[0m and \u001B]0;title\u0007plain\n", ConsoleStream.STDOUT)
        assertEquals(listOf("red and plain"), buffer.texts())
    }

    @Test
    fun `streams are assembled separately and tagged`() {
        val buffer = ConsoleBuffer()
        buffer.append("out-", ConsoleStream.STDOUT)
        buffer.append("err\n", ConsoleStream.STDERR)
        buffer.append("line\n", ConsoleStream.STDOUT)
        val lines = buffer.read(ConsoleQuery(100)).lines
        assertEquals(
            listOf("err" to ConsoleStream.STDERR, "out-line" to ConsoleStream.STDOUT),
            lines.map {
                it.text to
                    it.stream
            },
        )
    }

    @Test
    fun `the oldest lines are dropped at the line bound and reported as missed`() {
        val buffer = ConsoleBuffer(ConsoleLimits(maxLines = 3, maxChars = 1_000, maxLineChars = 100))
        (1..5).forEach { buffer.append("l$it\n", ConsoleStream.STDOUT) }
        val slice = buffer.read(ConsoleQuery(100, since = 0))
        assertEquals(listOf("l3", "l4", "l5"), slice.lines.map { it.text })
        assertEquals(3L, slice.firstSeq)
        assertEquals(5L, slice.lastSeq)
        assertEquals(2L, slice.missed)
    }

    @Test
    fun `the character bound drops old lines and long lines are split`() {
        val buffer = ConsoleBuffer(ConsoleLimits(maxLines = 100, maxChars = 10, maxLineChars = 4))
        buffer.append("abcdefghij\n", ConsoleStream.STDOUT)
        buffer.append("xyz\n", ConsoleStream.STDOUT)
        assertEquals(listOf("efgh", "ij", "xyz"), buffer.texts())
    }

    @Test
    fun `tail returns the last lines and says whether there are more`() {
        val buffer = ConsoleBuffer()
        (1..10).forEach { buffer.append("line $it\n", ConsoleStream.STDOUT) }
        val slice = buffer.read(ConsoleQuery(3))
        assertEquals(listOf("line 8", "line 9", "line 10"), slice.lines.map { it.text })
        assertTrue(slice.hasMore)
        assertEquals(10L, slice.nextCursor)
        assertFalse(buffer.read(ConsoleQuery(10)).hasMore)
    }

    @Test
    fun `polling with the cursor reads each line once`() {
        val buffer = ConsoleBuffer()
        (1..5).forEach { buffer.append("line $it\n", ConsoleStream.STDOUT) }
        val first = buffer.read(ConsoleQuery(2, since = 0))
        assertEquals(listOf("line 1", "line 2"), first.lines.map { it.text })
        assertTrue(first.hasMore)
        val second = buffer.read(ConsoleQuery(10, since = first.nextCursor))
        assertEquals(listOf("line 3", "line 4", "line 5"), second.lines.map { it.text })
        assertFalse(second.hasMore)
        buffer.append("line 6\n", ConsoleStream.STDOUT)
        assertEquals(listOf("line 6"), buffer.read(ConsoleQuery(10, since = second.nextCursor)).lines.map { it.text })
    }

    @Test
    fun `a filter applies before the limit and the cursor skips non-matching lines`() {
        val buffer = ConsoleBuffer()
        listOf("INFO a", "ERROR b", "INFO c", "ERROR d", "INFO e").forEach {
            buffer.append("$it\n", ConsoleStream.STDOUT)
        }
        val errors = Regex("ERROR")
        assertEquals(listOf("ERROR b", "ERROR d"), buffer.texts(ConsoleQuery(10, filter = errors)))
        val page = buffer.read(ConsoleQuery(1, since = 0, filter = errors))
        assertEquals(listOf("ERROR b"), page.lines.map { it.text })
        assertTrue(page.hasMore)
        val rest = buffer.read(ConsoleQuery(10, since = page.nextCursor, filter = errors))
        assertEquals(listOf("ERROR d"), rest.lines.map { it.text })
        assertEquals("all lines up to the last were examined", 5L, rest.nextCursor)
    }

    @Test
    fun `close completes unfinished lines and ignores later text`() {
        val buffer = ConsoleBuffer()
        buffer.append("prompt> ", ConsoleStream.STDOUT)
        assertEquals(emptyList<String>(), buffer.texts())
        assertEquals(listOf("prompt> "), buffer.snapshotAfter(0).partials.map { it.text })
        buffer.close()
        buffer.append("late\n", ConsoleStream.STDOUT)
        assertEquals(listOf("prompt> "), buffer.texts())
        assertTrue(buffer.isClosed)
    }

    @Test
    fun `every change bumps the version readers watch`() {
        val buffer = ConsoleBuffer()
        val before = buffer.version.value
        buffer.append("partial", ConsoleStream.STDOUT)
        val afterPartial = buffer.version.value
        assertNotEquals(before, afterPartial)
        buffer.close()
        assertNotEquals(afterPartial, buffer.version.value)
    }

    @Test
    fun `snapshotAfter returns the lines after the cursor`() {
        val buffer = ConsoleBuffer()
        (1..4).forEach { buffer.append("l$it\n", ConsoleStream.STDOUT) }
        val snapshot = buffer.snapshotAfter(2)
        assertEquals(listOf("l3", "l4"), snapshot.lines.map { it.text })
        assertEquals(4L, snapshot.lastSeq)
    }
}
