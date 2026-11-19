package com.github.sinlordep.mcpcompanion.vcshistory

import com.github.sinlordep.mcpcompanion.common.diff.TextDiff
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolError
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `mc_vcs_history` over a fake VCS: paging of the log, history of lines, blame segments, diff arguments. */
class VcsHistoryControlTest {

    private fun commit(n: Int) = CommitInfo("r$n", "Test Author", n * 1_000L, "Change $n\n\nDetails of $n")

    private inner class FakeVcs : VcsHistoryGateway {
        var history = (10 downTo 1).map(::commit)
        var lines: List<String?> = listOf("r1", "r1", "r3", "r3", "r3", null, "r1")
        val requests = mutableListOf<String>()
        var failure: ToolResult.Failure? = null

        override suspend fun log(path: String, max: Int): ToolResult<FileLog> {
            requests += "log $path $max"
            failure?.let { return it }
            return ToolResult.success(FileLog(path, "Git", history.take(max), history.size > max))
        }

        override suspend fun blame(path: String): ToolResult<FileBlame> {
            requests += "blame $path"
            failure?.let { return it }
            val commits = mapOf("r1" to commit(1), "r3" to commit(3))
            return ToolResult.success(FileBlame(path, "Git", "r10", false, lines, commits))
        }

        override suspend fun diff(path: String, from: String?, to: String?, maxLines: Int): ToolResult<FileDiff> {
            requests += "diff $path $from $to $maxLines"
            return ToolResult.success(FileDiff(path, "Git", from ?: "r10", to, TextDiff("", 0, 0, false)))
        }
    }

    private val vcs = FakeVcs()
    private val control = VcsHistoryControl(vcs)

    private fun call(action: VcsHistoryAction, arguments: VcsHistoryArguments) =
        runBlocking { control.execute(action, arguments) }

    private fun value(action: VcsHistoryAction, arguments: VcsHistoryArguments) =
        (call(action, arguments) as ToolResult.Success).value

    private fun code(action: VcsHistoryAction, arguments: VcsHistoryArguments) =
        (call(action, arguments) as ToolResult.Failure).error.code

    @Test
    fun `the log asks the VCS for no more than the page needs and pages newest first`() {
        val first = value(VcsHistoryAction.LOG, VcsHistoryArguments("A.java", limit = 3)) as VcsHistoryOutcome.Logged
        assertEquals(listOf("r10", "r9", "r8"), first.revisions.items.map { it.revision })
        assertTrue(first.revisions.hasMore)
        val next = value(VcsHistoryAction.LOG, VcsHistoryArguments("A.java", limit = 3, offset = 9))
        val last = (next as VcsHistoryOutcome.Logged).revisions
        assertEquals(listOf("r1"), last.items.map { it.revision })
        assertFalse(last.hasMore)
        assertEquals(listOf("log A.java 3", "log A.java 12"), vcs.requests)
    }

    @Test
    fun `the log of lines lists the commits that last changed them, newest first`() {
        val logged = value(VcsHistoryAction.LOG, VcsHistoryArguments("A.java", line = 2, endLine = 6))
        logged as VcsHistoryOutcome.Logged
        assertEquals(listOf("r3", "r1"), logged.revisions.items.map { it.revision })
        assertEquals(2..6, logged.lines)
        assertEquals(listOf("blame A.java"), vcs.requests)
    }

    @Test
    fun `blame merges consecutive lines of one revision into segments, optionally of a range`() {
        val all = value(VcsHistoryAction.BLAME, VcsHistoryArguments("A.java")) as VcsHistoryOutcome.Blamed
        assertEquals(
            listOf(
                BlameSegment(1, 2, "r1"),
                BlameSegment(3, 5, "r3"),
                BlameSegment(6, 6, null),
                BlameSegment(7, 7, "r1"),
            ),
            all.segments.items,
        )
        assertEquals(listOf("r1", "r3"), all.commits.map { it.revision })
        val range = value(VcsHistoryAction.BLAME, VcsHistoryArguments("A.java", line = 4)) as VcsHistoryOutcome.Blamed
        assertEquals(listOf(BlameSegment(4, 4, "r3")), range.segments.items)
        val page = value(VcsHistoryAction.BLAME, VcsHistoryArguments("A.java", limit = 1, offset = 1))
        assertEquals(listOf(BlameSegment(3, 5, "r3")), (page as VcsHistoryOutcome.Blamed).segments.items)
        assertTrue(page.segments.hasMore)
    }

    @Test
    fun `an empty file has no segments`() {
        vcs.lines = emptyList()
        val blamed = value(VcsHistoryAction.BLAME, VcsHistoryArguments("Empty.java")) as VcsHistoryOutcome.Blamed
        assertTrue(blamed.segments.items.isEmpty())
        assertTrue(blamed.commits.isEmpty())
    }

    @Test
    fun `line ranges, limits and diff arguments are checked before the VCS is asked`() {
        assertEquals(ErrorCode.INVALID_ARGUMENT, code(VcsHistoryAction.BLAME, VcsHistoryArguments("A", line = 0)))
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            code(VcsHistoryAction.BLAME, VcsHistoryArguments("A", line = 5, endLine = 4)),
        )
        assertEquals(ErrorCode.INVALID_ARGUMENT, code(VcsHistoryAction.BLAME, VcsHistoryArguments("A", endLine = 4)))
        assertEquals(ErrorCode.INVALID_ARGUMENT, code(VcsHistoryAction.BLAME, VcsHistoryArguments("A", line = 8)))
        assertEquals(ErrorCode.INVALID_ARGUMENT, code(VcsHistoryAction.LOG, VcsHistoryArguments("A", limit = 201)))
        assertEquals(ErrorCode.INVALID_ARGUMENT, code(VcsHistoryAction.LOG, VcsHistoryArguments(null)))
        assertEquals(ErrorCode.INVALID_ARGUMENT, code(VcsHistoryAction.DIFF, VcsHistoryArguments("A", to = "r2")))
        assertEquals(ErrorCode.INVALID_ARGUMENT, code(VcsHistoryAction.DIFF, VcsHistoryArguments("A", maxLines = 0)))
        assertEquals(ErrorCode.INVALID_ARGUMENT, code(VcsHistoryAction.DIFF, VcsHistoryArguments("A", line = 3)))
        assertEquals(ErrorCode.INVALID_ARGUMENT, code(VcsHistoryAction.LOG, VcsHistoryArguments("A", from = "r1")))
        assertTrue(vcs.requests.none { it.startsWith("log") || it.startsWith("diff") })
    }

    @Test
    fun `diff defaults to the working copy and passes revisions on`() {
        val working = value(VcsHistoryAction.DIFF, VcsHistoryArguments("A.java")) as VcsHistoryOutcome.Diffed
        assertNull(working.diff.toRevision)
        value(VcsHistoryAction.DIFF, VcsHistoryArguments("A.java", from = " r1 ", to = "r3", maxLines = 9))
        assertEquals(listOf("diff A.java null null 400", "diff A.java r1 r3 9"), vcs.requests)
    }

    @Test
    fun `VCS failures pass through`() {
        vcs.failure = ToolResult.Failure(ToolError(ErrorCode.UNSUPPORTED, "no VCS"))
        assertEquals(ErrorCode.UNSUPPORTED, code(VcsHistoryAction.LOG, VcsHistoryArguments("A")))
        assertEquals(ErrorCode.UNSUPPORTED, code(VcsHistoryAction.BLAME, VcsHistoryArguments("A")))
    }
}
