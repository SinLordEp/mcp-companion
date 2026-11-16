package com.github.sinlordep.mcpcompanion.sync

import com.github.sinlordep.mcpcompanion.common.time.Clock
import com.github.sinlordep.mcpcompanion.common.time.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The concurrency rule of `mc_sync`, per sync unit:
 *
 * - never two syncs of one unit at a time: a request waits until the running sync ends, whether `mc_sync` or the IDE
 *   started it, and only then starts its own;
 * - a request never reuses a sync that started before the request arrived (that sync may have read the build files
 *   before the agent's last edit); requests that wait at the same time share the one sync started after all of them
 *   arrived.
 *
 * Whether a sync runs is the IDE's word ([BuildSystemSync.activity]), not our own record: every run is watched, and a
 * run the IDE no longer works on but that produced no result ends as failed (see [Limits]). So a sync whose end the
 * IDE never reports cannot block its unit.
 *
 * Syncs run in [scope] (the project's), not in the caller's: a call that stops waiting leaves its sync running, and
 * [latest] shows it to later calls. One instance per project.
 */
class SyncCoordinator(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val limits: Limits = Limits(),
) {

    /**
     * The two bounds of the watch; nothing else waits on time.
     *
     * [start]: how long a run may wait for the IDE to begin any sync of its unit. The IDE queues the sync at once and
     * begins within milliseconds; there is no public event for "the IDE dropped the request" (e.g. Gradle's
     * before-sync tasks failed), so silence this long means it will not begin.
     *
     * [settle]: how long a run may stay without a result after the IDE reported no sync in progress. The result is
     * delivered on the IDE's sync thread right after its last reported phase (callback after the data import), so a
     * result that has not come within this time will not come.
     */
    data class Limits(val start: Duration = 2.minutes, val settle: Duration = 10.seconds)

    private val tickets = AtomicLong()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val runs = ConcurrentHashMap<String, SyncRun>()

    /** Marks a request's arrival; pass the ticket to [startOrJoin]. */
    fun arrive(): Long = tickets.incrementAndGet()

    /**
     * The sync that serves a request that arrived with [arrival]: one started after it arrived, else a new one started
     * once no sync of [unit] runs. Suspends while another sync runs; cancellable while it waits.
     */
    suspend fun startOrJoin(unit: SyncUnit, sync: BuildSystemSync, arrival: Long): Start =
        locks.computeIfAbsent(unit.key) { Mutex() }.withLock {
            val latest = runs[unit.key]
            if (latest != null && latest.ticket > arrival) {
                Start(latest, joined = true)
            } else {
                latest?.join()
                sync.activity(unit).first { !it.busy }
                val run = launch(unit, sync)
                runs[unit.key] = run
                Start(run, joined = false)
            }
        }

    /** The most recent sync `mc_sync` started for [key], running or finished. */
    fun latest(key: String): SyncRun? = runs[key]

    private fun launch(unit: SyncUnit, sync: BuildSystemSync): SyncRun {
        val activity = sync.activity(unit)
        // Read before the sync is requested: any start counted after this belongs to our request or a later one.
        val startsBefore = activity.value.starts
        val run = SyncRun(tickets.incrementAndGet(), clock.now())
        scope.launch {
            val outcome = runCatching { firstOf({ sync.sync(unit) }, { watch(activity, startsBefore) }) }
            run.complete(outcome.map { SyncRecord(it, run.startedAt, clock.now()) })
        }
        return run
    }

    /** Returns only when the IDE does not work on the run (any more) and gave no result; never while it works. */
    private suspend fun watch(activity: StateFlow<SyncActivity>, startsBefore: Long): SyncResult {
        clock.withTimeout(limits.start) { activity.first { it.starts > startsBefore } }
            ?: return SyncResult.Failed(NOT_STARTED, "No sync began within ${limits.start.inWholeSeconds} s.")
        while (true) {
            activity.first { !it.busy }
            clock.withTimeout(limits.settle) { activity.first { it.busy } }
                ?: return SyncResult.Failed(NO_RESULT)
        }
    }

    private suspend fun <T : Any> firstOf(a: suspend () -> T, b: suspend () -> T): T = coroutineScope {
        val first = async { a() }
        val second = async { b() }
        select {
            first.onAwait {
                second.cancel()
                it
            }
            second.onAwait {
                first.cancel()
                it
            }
        }
    }

    /** [joined]: the request shares a sync another request started. */
    data class Start(val run: SyncRun, val joined: Boolean)

    companion object {
        const val NOT_STARTED: String = "The IDE never began this sync, and reported no reason."
        const val NO_RESULT: String = "The IDE finished working on the sync but reported no result."
    }
}

/** A sync `mc_sync` started. */
class SyncRun internal constructor(val ticket: Long, val startedAt: Instant) {

    private val result = CompletableDeferred<SyncRecord>()

    /** The record once the sync ended; null while it runs, or when it ended with an exception. */
    @Volatile
    var finished: SyncRecord? = null
        private set

    val isRunning: Boolean get() = !result.isCompleted

    suspend fun await(): SyncRecord = result.await()

    /** Suspends until the sync ended, however it ended. */
    suspend fun join() = result.join()

    internal fun complete(outcome: Result<SyncRecord>) {
        finished = outcome.getOrNull()
        result.completeWith(outcome)
    }
}
