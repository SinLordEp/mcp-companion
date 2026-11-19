package com.github.sinlordep.mcpcompanion.common.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rendering of changed line blocks as unified diffs; the blocks themselves come from the IDE's comparison. */
class UnifiedDiffTest {

    private val before = (1..20).map { "line $it" }

    @Test
    fun `one change gets a hunk with three lines of context`() {
        val after = before.toMutableList().apply { this[9] = "changed" }
        val diff = UnifiedDiff.render("a.txt", "a.txt", before, after, listOf(ChangedLines(9, 10, 9, 10)), 100)
        assertEquals(
            listOf(
                "--- a/a.txt",
                "+++ b/a.txt",
                "@@ -7,7 +7,7 @@",
                " line 7",
                " line 8",
                " line 9",
                "-line 10",
                "+changed",
                " line 11",
                " line 12",
                " line 13",
            ),
            diff.text.lines(),
        )
        assertEquals(1, diff.added)
        assertEquals(1, diff.removed)
        assertFalse(diff.truncated)
    }

    @Test
    fun `changes close together share a hunk, distant ones do not`() {
        val changes = listOf(ChangedLines(2, 3, 2, 3), ChangedLines(6, 7, 6, 7), ChangedLines(18, 19, 18, 19))
        val diff = UnifiedDiff.render("a", "a", before, before, changes, 100)
        assertEquals(listOf("@@ -1,10 +1,10 @@", "@@ -16,5 +16,5 @@"), diff.text.lines().filter { it.startsWith("@@") })
    }

    @Test
    fun `insertions, deletions and new files use the unified header forms`() {
        val inserted = UnifiedDiff.render(
            "a",
            "a",
            listOf("x"),
            listOf("x", "y"),
            listOf(ChangedLines(1, 1, 1, 2)),
            100,
        )
        assertEquals("@@ -1 +1,2 @@", inserted.text.lines()[2])
        val created = UnifiedDiff.render(null, "n", emptyList(), listOf("a", "b"), listOf(ChangedLines(0, 0, 0, 2)), 9)
        assertEquals(listOf("--- /dev/null", "+++ b/n", "@@ -0,0 +1,2 @@", "+a", "+b"), created.text.lines())
        val deleted = UnifiedDiff.render("d", null, listOf("a"), emptyList(), listOf(ChangedLines(0, 1, 0, 0)), 9)
        assertEquals(listOf("--- a/d", "+++ /dev/null", "@@ -1 +0,0 @@", "-a"), deleted.text.lines())
    }

    @Test
    fun `the output stops at maxLines and says so, the counts cover everything`() {
        val after = before.map { "$it!" }
        val diff = UnifiedDiff.render("a", "a", before, after, listOf(ChangedLines(0, 20, 0, 20)), 5)
        assertEquals(5, diff.text.lines().size)
        assertTrue(diff.truncated)
        assertEquals(20, diff.added)
        assertEquals(20, diff.removed)
    }

    @Test
    fun `no changes is an empty diff`() {
        val diff = UnifiedDiff.render("a", "a", before, before, emptyList(), 10)
        assertTrue(diff.isEmpty)
        assertEquals("", diff.text)
    }

    @Test
    fun `lines of a text ignore the break after the last line and accept every line separator`() {
        assertEquals(listOf("a", "b"), UnifiedDiff.lines(UnifiedDiff.normalised("a\r\nb\r\n")))
        assertEquals(listOf("a", "", "b"), UnifiedDiff.lines(UnifiedDiff.normalised("a\r\rb")))
        assertEquals(emptyList<String>(), UnifiedDiff.lines(""))
    }
}
