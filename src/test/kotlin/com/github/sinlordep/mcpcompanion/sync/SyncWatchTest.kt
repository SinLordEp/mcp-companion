package com.github.sinlordep.mcpcompanion.sync

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.testing.ManualClock
import com.github.sinlordep.mcpcompanion.testing.settleUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The coordinator's watch over the IDE's activity: a sync whose end the IDE never reports must not block its unit.
 * Fake build systems and a manual clock; nothing sleeps.
 */
class SyncWatchTest {

    private val clock = ManualClock()
    private val gradle = FakeBuildSystem("gradle", "app")
    private val environment = FakeSyncEnvironment(gradle)

    private fun test(block: suspend CoroutineScope.(SyncControl) -> Unit) = runBlocking {
        val syncs = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            block(SyncControl.create(environment, SyncCoordinator(syncs, clock), clock))
        } finally {
            syncs.cancel()
        }
    }

    private suspend fun SyncControl.status(arguments: SyncArguments) =
        execute(SyncAction.STATUS, arguments).value() as SyncOutcome.Status

    private fun ToolResult<SyncOutcome>.synced() = value() as SyncOutcome.Synced

    private val gradleApp = SyncArguments(buildSystem = "gradle", project = "app")

    /** Lets every runnable coroutine of the single-threaded test run, so that cancelled sleepers are gone. */
    private suspend fun quiet() = repeat(1_000) { yield() }

    private suspend fun SyncControl.gradleAppStatus() = status(gradleApp).buildSystems.single().units.single()

    /**
     * Point of the watch: the IDE worked on the sync and went idle, but the result never came (e.g. an exception after
     * its last event). The run ends as failed after the settle bound; status no longer shows it running; the next
     * request starts a new sync instead of waiting for the dead one.
     */
    @Test
    fun `a sync that the IDE finished without a result ends as failed and frees its unit`() = test { control ->
        val call = async { control.execute(SyncAction.SYNC, gradleApp.copy(timeoutSeconds = 600)) }
        settleUntil("sync started") { gradle.started.size == 1 }
        gradle.goSilent(0)
        quiet()
        assertEquals("the call's deadline and the settle bound", 2, clock.sleeping)
        clock.advance(SyncCoordinator.Limits().settle)
        val unit = call.await().synced().units.single()
        assertEquals(UnitSyncState.FINISHED, unit.state)
        assertEquals(SyncResult.Failed(SyncCoordinator.NO_RESULT), unit.run!!.record!!.result)
        assertEquals(1, gradle.cancelled)
        assertFalse(control.gradleAppStatus().running)

        val next = async { control.execute(SyncAction.SYNC, gradleApp) }
        settleUntil("a new sync started") { gradle.started.size == 2 }
        gradle.finish(1)
        assertEquals(SyncResult.Succeeded(), next.await().synced().units.single().run!!.record!!.result)
    }

    /** Idle for a moment between two reported phases (e.g. resolve ended, data import not yet begun) is not the end. */
    @Test
    fun `a short idle gap between phases does not end the sync`() = test { control ->
        val call = async { control.execute(SyncAction.SYNC, gradleApp) }
        settleUntil("sync started") { gradle.started.size == 1 }
        gradle.goSilent(0)
        quiet()
        assertEquals("the call's deadline and the settle bound", 2, clock.sleeping)
        clock.advance(5.seconds)
        gradle.setBusy(gradle.started[0].first, true)
        repeat(100) { yield() }
        clock.advance(30.seconds)
        repeat(100) { yield() }
        assertTrue(control.gradleAppStatus().running)
        gradle.finish(0)
        assertEquals(SyncResult.Succeeded(), call.await().synced().units.single().run!!.record!!.result)
        assertEquals(0, gradle.cancelled)
    }

    /** The IDE never shows the requested sync (e.g. it dropped the request): failed after the start bound. */
    @Test
    fun `a sync the IDE never begins ends as failed after the start bound`() = test { control ->
        gradle.ideBegins = false
        val call = async { control.execute(SyncAction.SYNC, gradleApp.copy(timeoutSeconds = 600)) }
        settleUntil("sync requested") { gradle.started.size == 1 }
        quiet()
        assertTrue("our own record says running", control.gradleAppStatus().running)
        clock.advance(SyncCoordinator.Limits().start)
        val record = call.await().synced().units.single().run!!.record!!
        assertEquals(SyncCoordinator.NOT_STARTED, (record.result as SyncResult.Failed).message)
        assertFalse(control.gradleAppStatus().running)

        gradle.ideBegins = true
        val next = async { control.execute(SyncAction.SYNC, gradleApp) }
        settleUntil("a new sync started") { gradle.started.size == 2 }
        gradle.finish(1)
        next.await()
    }

    /** A dead run found by a waiting request: the request does not wait for it longer than the watch allows. */
    @Test
    fun `a request waiting behind a dead sync gets its own sync`() = test { control ->
        val first = async { control.execute(SyncAction.SYNC, gradleApp.copy(timeoutSeconds = 1)) }
        settleUntil("first sync started") { gradle.started.size == 1 }
        clock.advance(1.seconds)
        assertEquals(UnitSyncState.RUNNING, first.await().synced().units.single().state)
        val second = async { control.execute(SyncAction.SYNC, gradleApp.copy(timeoutSeconds = 600)) }
        repeat(100) { yield() }
        assertEquals("waits while the IDE works", 1, gradle.started.size)
        gradle.goSilent(0)
        quiet()
        clock.advance(SyncCoordinator.Limits().settle)
        settleUntil("second sync started") { gradle.started.size == 2 }
        gradle.finish(1)
        assertEquals(SyncResult.Succeeded(), second.await().synced().units.single().run!!.record!!.result)
    }
}
