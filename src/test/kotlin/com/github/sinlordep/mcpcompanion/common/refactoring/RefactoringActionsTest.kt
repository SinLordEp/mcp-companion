package com.github.sinlordep.mcpcompanion.common.refactoring

import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.symbols.FileOrigin
import com.github.sinlordep.mcpcompanion.common.symbols.LocatedSymbol
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols.code
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols.value
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefactoringActionsTest {

    private object Plan : RefactoringPlan

    /** Returns [analyses] one after another (the last one repeats) and the [attempts] in order. */
    private class FakeGateway(
        private val analyses: List<ToolResult<RefactoringAnalysis>>,
        private val attempts: List<RefactoringAttempt> = listOf(RefactoringAttempt.Applied(listOf("a/A.java"))),
    ) : RefactoringGateway<String> {
        var analysed = 0
        var applied = 0
        val details = ArrayList<String>()

        override suspend fun analyse(target: LocatedSymbol, details: String): ToolResult<RefactoringAnalysis> {
            this.details += details
            return analyses[minOf(analysed++, analyses.lastIndex)]
        }

        override suspend fun apply(plan: RefactoringPlan): ToolResult<RefactoringAttempt> =
            ToolResult.success(attempts[minOf(applied++, attempts.lastIndex)])
    }

    private fun analysis(files: Int, conflicts: Int = 0) = ToolResult.success(
        RefactoringAnalysis(
            files = List(files) { AffectedFile("f/F${files - it}.java", FileOrigin.PROJECT, it) },
            referenceCount = files * 2,
            conflicts = List(conflicts) { RefactoringConflict("conflict $it", FakeSymbols.site(conflicts - it)) },
            plan = Plan,
        ),
    )

    private fun run(gateway: FakeGateway, action: RefactoringAction, limit: Int = 100, offset: Int = 0) = runBlocking {
        RefactoringActions(FakeSymbols.locating(FakeSymbols.symbol("Foo")), gateway, "moved")
            .execute(
                action,
                RefactoringRequest(FakeSymbols.ANY_POSITION, "dest", PageRequest.of(limit, offset).value()),
            )
    }

    @Test
    fun `preview pages the files in path order, lists the conflicts in reading order and never applies`() {
        val gateway = FakeGateway(listOf(analysis(files = 5, conflicts = 2)))
        val outcome = run(
            gateway,
            RefactoringAction.PREVIEW,
            limit = 2,
            offset = 2,
        ).value() as RefactoringOutcome.Previewed
        assertEquals(5, outcome.fileCount)
        assertEquals(10, outcome.referenceCount)
        assertEquals(listOf("f/F3.java", "f/F4.java"), outcome.files.items.map { it.path })
        assertTrue(outcome.files.hasMore)
        assertEquals(4, outcome.files.nextOffset)
        assertEquals(listOf("conflict 1", "conflict 0"), outcome.conflicts.map { it.message })
        assertEquals(2, outcome.conflictCount)
        assertEquals(listOf("dest"), gateway.details)
        assertEquals(0, gateway.applied)
    }

    @Test
    fun `preview lists a bounded number of conflicts but counts all`() {
        val many = RefactoringActions.MAX_CONFLICTS + 7
        val outcome = run(FakeGateway(listOf(analysis(1, many))), RefactoringAction.PREVIEW).value()
            as RefactoringOutcome.Previewed
        assertEquals(RefactoringActions.MAX_CONFLICTS, outcome.conflicts.size)
        assertEquals(many, outcome.conflictCount)
    }

    @Test
    fun `apply refuses with CONFLICT while conflicts exist`() {
        val gateway = FakeGateway(listOf(analysis(files = 1, conflicts = 1)))
        val result = run(gateway, RefactoringAction.APPLY)
        assertEquals(ErrorCode.CONFLICT, result.code())
        assertTrue((result as ToolResult.Failure).error.message.contains("conflict 0"))
        assertEquals(0, gateway.applied)
    }

    @Test
    fun `apply reports the changed files`() {
        val outcome = run(
            FakeGateway(listOf(analysis(2))),
            RefactoringAction.APPLY,
        ).value() as RefactoringOutcome.Applied
        assertEquals(listOf("a/A.java"), outcome.changedFiles)
        assertEquals(4, outcome.referenceCount)
    }

    @Test
    fun `apply analyses again when the code changed, and gives up after a few attempts`() {
        val stale = RefactoringAttempt.Stale
        val retried = FakeGateway(listOf(analysis(1)), listOf(stale, RefactoringAttempt.Applied(emptyList())))
        assertTrue(run(retried, RefactoringAction.APPLY).value() is RefactoringOutcome.Applied)
        assertEquals(2, retried.analysed)

        val changing = FakeGateway(listOf(analysis(1)), listOf(stale))
        assertEquals(ErrorCode.CONFLICT, run(changing, RefactoringAction.APPLY).code())
        assertEquals(3, changing.applied)
    }

    @Test
    fun `failures of locating and analysing pass through`() {
        val gateway = FakeGateway(listOf(ToolResult.failure(ErrorCode.UNSUPPORTED, "no")))
        assertEquals(ErrorCode.UNSUPPORTED, run(gateway, RefactoringAction.PREVIEW).code())
        val located = runBlocking {
            RefactoringActions(FakeSymbols.failing(ErrorCode.NOT_FOUND), gateway, "moved").execute(
                RefactoringAction.APPLY,
                RefactoringRequest(FakeSymbols.ANY_POSITION, "dest", PageRequest.FIRST),
            )
        }
        assertEquals(ErrorCode.NOT_FOUND, located.code())
        assertFalse(gateway.applied > 0)
    }
}
