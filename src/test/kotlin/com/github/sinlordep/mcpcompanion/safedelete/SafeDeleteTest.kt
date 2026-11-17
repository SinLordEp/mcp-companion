package com.github.sinlordep.mcpcompanion.safedelete

import com.github.sinlordep.mcpcompanion.common.paging.PageRequest
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolHandle
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols.code
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols.value
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeDeleteTest {

    private object Plan : DeletionPlan

    /** Returns [analyses] one after another (the last one repeats) and the [attempts] in order. */
    private class FakeGateway(
        private val analyses: List<ToolResult<SafeDeleteAnalysis>>,
        private val attempts: List<DeletionAttempt> = listOf(DeletionAttempt.Deleted),
    ) : SafeDeleteGateway {
        var analysed = 0
        var deleted = 0

        override suspend fun analyse(target: SymbolHandle): ToolResult<SafeDeleteAnalysis> =
            analyses[minOf(analysed++, analyses.lastIndex)]

        override suspend fun delete(plan: DeletionPlan): ToolResult<DeletionAttempt> =
            ToolResult.success(attempts[minOf(deleted++, attempts.lastIndex)])
    }

    private fun analysis(blockers: Int, cleanups: Int = 0) = ToolResult.success(
        SafeDeleteAnalysis(
            blockers = List(blockers) { Blocker(BlockerKind.entries[it % 3], FakeSymbols.site(it + 1), null) },
            cleanups = cleanups,
            alsoDeleted = listOf(FakeSymbols.symbol("Override")),
            plan = Plan,
        ),
    )

    private val target = FakeSymbols.symbol("Foo")

    private fun run(gateway: FakeGateway, action: SafeDeleteAction, limit: Int = 100, offset: Int = 0) = runBlocking {
        SafeDelete(FakeSymbols.locating(target), gateway)
            .execute(action, SafeDeleteRequest(FakeSymbols.ANY_POSITION, PageRequest.of(limit, offset).value()))
    }

    @Test
    fun `preview pages the blockers and never deletes`() {
        val gateway = FakeGateway(listOf(analysis(blockers = 5, cleanups = 2)))
        val outcome = run(
            gateway,
            SafeDeleteAction.PREVIEW,
            limit = 2,
            offset = 2,
        ).value() as SafeDeleteOutcome.Previewed
        assertEquals(5, outcome.blockerCount)
        assertEquals(listOf(3, 4), outcome.blockers.items.map { it.site?.location?.line })
        assertTrue(outcome.blockers.hasMore)
        assertEquals(2, outcome.cleanups)
        assertEquals(0, gateway.deleted)
    }

    @Test
    fun `apply refuses with blockers and never deletes`() {
        val gateway = FakeGateway(listOf(analysis(blockers = 3)))
        val result = run(gateway, SafeDeleteAction.APPLY)
        assertEquals(ErrorCode.CONFLICT, result.code())
        val message = (result as ToolResult.Failure).error.message
        assertTrue(message, "3 blocker(s)" in message && "1 reference" in message && "1 dependent" in message)
        assertEquals(0, gateway.deleted)
    }

    @Test
    fun `apply deletes when nothing blocks`() {
        val gateway = FakeGateway(listOf(analysis(blockers = 0, cleanups = 1)))
        assertEquals(
            SafeDeleteOutcome.Deleted(
                target,
                cleanups = 1,
                alsoDeleted = listOf(FakeSymbols.symbol("Override")),
            ).toString(),
            run(gateway, SafeDeleteAction.APPLY).value().toString(),
        )
        assertEquals(1, gateway.deleted)
    }

    @Test
    fun `apply analyses again when the code changed, and refuses if usages appeared meanwhile`() {
        val retried = FakeGateway(listOf(analysis(0)), listOf(DeletionAttempt.Stale, DeletionAttempt.Deleted))
        assertTrue(run(retried, SafeDeleteAction.APPLY).value() is SafeDeleteOutcome.Deleted)
        assertEquals(2, retried.analysed)

        val appeared = FakeGateway(listOf(analysis(0), analysis(1)), listOf(DeletionAttempt.Stale))
        assertEquals(ErrorCode.CONFLICT, run(appeared, SafeDeleteAction.APPLY).code())
        assertEquals(1, appeared.deleted)
    }

    @Test
    fun `apply gives up after a few stale attempts`() {
        val gateway = FakeGateway(listOf(analysis(0)), listOf(DeletionAttempt.Stale))
        assertEquals(ErrorCode.CONFLICT, run(gateway, SafeDeleteAction.APPLY).code())
        assertEquals(3, gateway.deleted)
    }

    @Test
    fun `analysis and locator failures are passed on`() {
        val gateway = FakeGateway(listOf(ToolResult.failure(ErrorCode.INVALID_ARGUMENT, "library")))
        assertEquals(ErrorCode.INVALID_ARGUMENT, run(gateway, SafeDeleteAction.APPLY).code())
        assertEquals(ErrorCode.INVALID_ARGUMENT, run(gateway, SafeDeleteAction.PREVIEW).code())
        val notFound = runBlocking {
            SafeDelete(FakeSymbols.failing(ErrorCode.NOT_FOUND), gateway)
                .execute(SafeDeleteAction.APPLY, SafeDeleteRequest(FakeSymbols.ANY_POSITION, PageRequest.FIRST))
        }
        assertEquals(ErrorCode.NOT_FOUND, notFound.code())
        assertFalse(gateway.deleted > 0)
    }
}
