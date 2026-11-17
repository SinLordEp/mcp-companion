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

/** `status` and `sync` over fake build systems; no IDE, no real time. */
class SyncControlTest {

    private val clock = ManualClock()
    private val gradle = FakeBuildSystem("gradle", "app", "../lib")
    private val maven = FakeBuildSystem("maven", "svc")
    private val environment = FakeSyncEnvironment(gradle, maven)

    private fun test(block: suspend CoroutineScope.(SyncControl) -> Unit) = runBlocking {
        val syncs = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            block(SyncControl.create(environment, SyncCoordinator(syncs, clock), clock))
        } finally {
            syncs.cancel()
        }
    }

    private suspend fun SyncControl.status(arguments: SyncArguments = SyncArguments()) =
        execute(SyncAction.STATUS, arguments).value() as SyncOutcome.Status

    private fun ToolResult<SyncOutcome>.synced() = value() as SyncOutcome.Synced

    private val gradleApp = SyncArguments(buildSystem = "gradle", project = "app")

    @Test
    fun `status lists build systems, linked projects, needsReload and running syncs`() = test { control ->
        gradle.ideBusy(true)
        maven.needsReload = null
        val status = control.status()
        assertEquals(listOf("gradle", "maven"), status.buildSystems.map { it.buildSystem.id })
        val gradleStatus = status.buildSystems[0]
        assertEquals(true, gradleStatus.needsReload)
        assertEquals(listOf(listOf("app"), listOf("../lib")), gradleStatus.units.map { it.unit.projects })
        assertTrue(gradleStatus.units.all { it.running && it.last == null })
        assertNull(status.buildSystems[1].needsReload)
        assertEquals(
            listOf("maven"),
            control.status(SyncArguments(buildSystem = "MAVEN")).buildSystems.map {
                it.buildSystem.id
            },
        )
    }

    @Test
    fun `a sync waits for its end and reports the result with its duration`() = test { control ->
        val call = async { control.execute(SyncAction.SYNC, gradleApp) }
        settleUntil("sync started") { gradle.started.size == 1 }
        assertEquals("gradle:app", gradle.started.single().first.key)
        clock.advance(3.seconds)
        gradle.finish(0)
        val unit = call.await().synced().units.single()
        assertEquals(UnitSyncState.FINISHED, unit.state)
        val record = unit.run!!.record!!
        assertEquals(SyncResult.Succeeded(), record.result)
        assertEquals(Duration.ofSeconds(3), Duration.between(record.startedAt, record.endedAt))
        assertFalse(unit.joined)
    }

    @Test
    fun `without filters every linked project syncs, in parallel`() = test { control ->
        val call = async { control.execute(SyncAction.SYNC, SyncArguments()) }
        settleUntil("three syncs started") { gradle.started.size == 2 && maven.started.size == 1 }
        gradle.finish(0)
        gradle.finish(1, SyncResult.Failed("Could not resolve plugin", "details"))
        maven.finish(0)
        val units = call.await().synced().units
        assertEquals(listOf("gradle:app", "gradle:../lib", "maven:svc"), units.map { it.unit.key })
        assertEquals(SyncResult.Failed("Could not resolve plugin", "details"), units[1].run!!.record!!.result)
    }

    @Test
    fun `when the timeout passes the sync goes on and a later status shows its result`() = test { control ->
        val call = async { control.execute(SyncAction.SYNC, gradleApp.copy(timeoutSeconds = 5)) }
        settleUntil("sync started") { gradle.started.size == 1 }
        settleUntil("deadline armed") { clock.sleeping == 1 }
        clock.advance(5.seconds)
        val unit = call.await().synced().units.single()
        assertEquals(UnitSyncState.RUNNING, unit.state)
        assertNull(unit.run!!.record)

        val running = control.status(gradleApp).buildSystems.single().units.single()
        assertTrue(running.running)
        assertNull(running.last!!.record)

        gradle.finish(0, SyncResult.Cancelled)
        settleUntil("sync finished") { gradle.started.single().second.isCompleted }
        repeat(100) { yield() }
        val finished = control.status(gradleApp).buildSystems.single().units.single()
        assertFalse(finished.running)
        assertEquals(SyncResult.Cancelled, finished.last!!.record!!.result)
    }

    @Test
    fun `a sync the IDE runs is waited for, then a new one starts`() = test { control ->
        gradle.ideBusy(true)
        val call = async { control.execute(SyncAction.SYNC, gradleApp) }
        repeat(100) { yield() }
        assertTrue("nothing starts while the IDE syncs", gradle.started.isEmpty())
        gradle.ideBusy(false)
        settleUntil("own sync started") { gradle.started.size == 1 }
        gradle.finish(0)
        assertEquals(UnitSyncState.FINISHED, call.await().synced().units.single().state)
    }

    @Test
    fun `when the timeout passes while the IDE syncs nothing is started`() = test { control ->
        gradle.ideBusy(true)
        val call = async { control.execute(SyncAction.SYNC, gradleApp.copy(timeoutSeconds = 5)) }
        settleUntil("deadline armed") { clock.sleeping == 1 }
        clock.advance(5.seconds)
        val unit = call.await().synced().units.single()
        assertEquals(UnitSyncState.NOT_STARTED, unit.state)
        assertNull(unit.run)
        gradle.ideBusy(false)
        repeat(100) { yield() }
        assertTrue(gradle.started.isEmpty())
    }

    /**
     * The concurrency rule: B and C arrive while A's sync runs. Neither may use A's sync (it may predate their edits),
     * neither may start a second sync while it runs; B starts one after it and C, which waited at the same time,
     * shares B's.
     */
    @Test
    fun `requests during a sync wait for it and then share one new sync`() = test { control ->
        val a = async { control.execute(SyncAction.SYNC, gradleApp) }
        settleUntil("A's sync started") { gradle.started.size == 1 }
        val b = async { control.execute(SyncAction.SYNC, gradleApp) }
        val c = async { control.execute(SyncAction.SYNC, gradleApp) }
        repeat(100) { yield() }
        assertEquals("no second sync while one runs", 1, gradle.started.size)

        gradle.finish(0)
        settleUntil("the next sync started") { gradle.started.size == 2 }
        repeat(100) { yield() }
        assertEquals(2, gradle.started.size)
        gradle.finish(1)

        val units = listOf(a, b, c).map { it.await().synced().units.single() }
        assertEquals(listOf(false, false, true), units.map { it.joined })
        assertEquals(units[1].run!!.startedAt, units[2].run!!.startedAt)
        assertEquals(2, gradle.started.size)
    }

    @Test
    fun `waitForIndexing waits after successful syncs only`() = test { control ->
        val call = async { control.execute(SyncAction.SYNC, gradleApp.copy(waitForIndexing = true)) }
        settleUntil("sync started") { gradle.started.size == 1 }
        gradle.finish(0)
        settleUntil("waiting for indexing") { environment.indexingWaits == 1 }
        environment.indexing.complete(Unit)
        assertEquals(IndexingWait.DONE, call.await().synced().indexing)

        val failed = async { control.execute(SyncAction.SYNC, gradleApp.copy(waitForIndexing = true)) }
        settleUntil("second sync started") { gradle.started.size == 2 }
        gradle.finish(1, SyncResult.Failed("broken"))
        assertEquals(IndexingWait.SKIPPED, failed.await().synced().indexing)
        assertEquals(1, environment.indexingWaits)

        val plain = async { control.execute(SyncAction.SYNC, gradleApp) }
        settleUntil("third sync started") { gradle.started.size == 3 }
        gradle.finish(2)
        assertEquals(IndexingWait.NOT_REQUESTED, plain.await().synced().indexing)
    }

    @Test
    fun `indexing that outlasts the timeout is reported`() = test { control ->
        val call =
            async { control.execute(SyncAction.SYNC, gradleApp.copy(timeoutSeconds = 10, waitForIndexing = true)) }
        settleUntil("sync started") { gradle.started.size == 1 }
        clock.advance(4.seconds)
        gradle.finish(0)
        settleUntil("waiting for indexing") { environment.indexingWaits == 1 && clock.sleeping == 1 }
        clock.advance(6.seconds)
        val synced = call.await().synced()
        assertEquals(IndexingWait.TIMED_OUT, synced.indexing)
        assertEquals(10_000, synced.waitedMillis)
    }

    @Test
    fun `sync is refused where it cannot or must not run`() = test { control ->
        environment.trusted = false
        assertEquals(ErrorCode.CONFLICT, control.execute(SyncAction.SYNC, SyncArguments()).code())
        assertTrue("status still works", control.execute(SyncAction.STATUS, SyncArguments()) is ToolResult.Success)
        environment.trusted = true

        assertEquals(ErrorCode.NOT_FOUND, control.execute(SyncAction.SYNC, SyncArguments(buildSystem = "sbt")).code())
        assertEquals(ErrorCode.NOT_FOUND, control.execute(SyncAction.SYNC, SyncArguments(project = "nowhere")).code())
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            control.execute(SyncAction.SYNC, SyncArguments(timeoutSeconds = 0)).code(),
        )
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            control.execute(SyncAction.SYNC, SyncArguments(timeoutSeconds = 601)).code(),
        )
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            control.execute(SyncAction.STATUS, SyncArguments(timeoutSeconds = 5)).code(),
        )
        assertTrue(gradle.started.isEmpty())
    }

    @Test
    fun `without build system support status is empty and sync unsupported`() = test { control ->
        environment.systems.clear()
        assertEquals(emptyList<BuildSystemStatus>(), control.status().buildSystems)
        assertEquals(ErrorCode.UNSUPPORTED, control.execute(SyncAction.SYNC, SyncArguments()).code())
    }

    @Test
    fun `a build system without linked projects cannot sync`() = test { control ->
        environment.systems.clear()
        environment.systems += FakeBuildSystem("maven")
        assertEquals(ErrorCode.NOT_FOUND, control.execute(SyncAction.SYNC, SyncArguments()).code())
    }

    @Test
    fun `the project filter accepts the forms the environment renders`() = test { control ->
        val call = async { control.execute(SyncAction.SYNC, SyncArguments(project = "/abs/../lib")) }
        settleUntil("sync started") { gradle.started.size == 1 }
        assertEquals("gradle:../lib", gradle.started.single().first.key)
        gradle.finish(0)
        call.await()
    }
}
