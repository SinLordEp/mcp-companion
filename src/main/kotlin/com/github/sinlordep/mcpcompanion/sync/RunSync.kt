package com.github.sinlordep.mcpcompanion.sync

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.time.Clock
import com.github.sinlordep.mcpcompanion.common.time.timeoutOf
import com.github.sinlordep.mcpcompanion.common.time.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration
import java.time.Duration as JavaDuration

/**
 * `sync`: re-imports the selected linked projects (all by default) and waits until each sync ends, at most
 * `timeoutSeconds`. Units sync in parallel; per unit the [SyncCoordinator]'s rule applies. With `waitForIndexing` it
 * then waits, within the same timeout, until the IDE reports no configuration activity and is in smart mode.
 */
class RunSync(
    private val environment: SyncEnvironment,
    private val selection: UnitSelection,
    private val coordinator: SyncCoordinator,
    private val clock: Clock,
) : SyncActionHandler {

    override val action = SyncAction.SYNC
    override val parameters = setOf(
        SyncParameter.BUILD_SYSTEM,
        SyncParameter.PROJECT,
        SyncParameter.TIMEOUT,
        SyncParameter.WAIT_FOR_INDEXING,
    )

    override suspend fun handle(request: SyncArguments): ToolResult<SyncOutcome> =
        timeoutOf(request.timeoutSeconds, DEFAULT_TIMEOUT, MAX_TIMEOUT).flatMap { timeout ->
            if (!environment.isTrusted()) return@flatMap untrusted()
            selection.select(request).flatMap { selected ->
                when {
                    selected.systems.isEmpty() -> unsupported()
                    selected.units.isEmpty() -> nothingLinked(selected)
                    else -> ToolResult.success(syncAll(selected.units, timeout, request.waitForIndexing == true))
                }
            }
        }

    private suspend fun syncAll(
        units: List<Pair<BuildSystemSync, SyncUnit>>,
        timeout: Duration,
        waitForIndexing: Boolean,
    ): SyncOutcome.Synced {
        val start = clock.now()
        val arrival = coordinator.arrive()
        val results = coroutineScope {
            units.map { (sync, unit) -> async { syncUnit(sync, unit, arrival, timeout) } }.awaitAll()
        }
        val succeeded = results.all { it.run?.record?.result is SyncResult.Succeeded }
        val indexing = when {
            !waitForIndexing -> IndexingWait.NOT_REQUESTED

            !succeeded -> IndexingWait.SKIPPED

            else -> {
                val left = timeout - elapsedSince(start)
                val done = left.isPositive() && clock.withTimeout(left) { environment.awaitIndexing() } != null
                if (done) IndexingWait.DONE else IndexingWait.TIMED_OUT
            }
        }
        return SyncOutcome.Synced(results, indexing, elapsedSince(start).inWholeMilliseconds)
    }

    private suspend fun syncUnit(sync: BuildSystemSync, unit: SyncUnit, arrival: Long, timeout: Duration): UnitSync {
        var started: SyncCoordinator.Start? = null
        val record = clock.withTimeout(timeout) {
            coordinator.startOrJoin(unit, sync, arrival).also { started = it }.run.await()
        }
        val start = started
        return when {
            start == null -> UnitSync(unit, UnitSyncState.NOT_STARTED, joined = false, run = null)
            record == null -> UnitSync(unit, UnitSyncState.RUNNING, start.joined, LastSync(start.run.startedAt, null))
            else -> UnitSync(unit, UnitSyncState.FINISHED, start.joined, LastSync(record.startedAt, record))
        }
    }

    private fun elapsedSince(start: Instant): Duration = JavaDuration.between(start, clock.now()).toKotlinDuration()

    private fun untrusted(): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.CONFLICT,
        "The project is not trusted (safe mode); the IDE does not run its build scripts.",
        "Trust the project in the IDE first.",
    )

    private fun unsupported(): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.UNSUPPORTED,
        "This IDE has no build system support that mc_sync can drive (Gradle, Maven or another external system).",
        null,
    )

    private fun nothingLinked(selected: Selection): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.NOT_FOUND,
        "No ${selected.systems.joinToString(" or ") { it.sync.buildSystem.name }} project is linked to this project.",
        "Link the build project in the IDE first (e.g. open its build file as a project).",
    )

    private companion object {
        val DEFAULT_TIMEOUT: Duration = 60.seconds
        val MAX_TIMEOUT: Duration = 600.seconds
    }
}
