package com.github.sinlordep.mcpcompanion.sync

import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException

/**
 * A build system whose syncs the test ends by hand: every [sync] call makes the IDE busy (unless [ideBegins] is false)
 * and waits for [finish]; [goSilent] makes the IDE idle without a result. [ideBusy] simulates a sync the IDE started.
 */
class FakeBuildSystem(id: String, vararg projects: String) : BuildSystemSync {

    override val buildSystem = BuildSystem(id, id.replaceFirstChar { it.uppercase() })

    private val units = projects.map { SyncUnit("$id:$it", buildSystem, listOf(it), "/abs/$it") }

    private val activities = units.associate { it.key to MutableStateFlow(SyncActivity.IDLE) }

    var needsReload: Boolean? = true

    /** False: the IDE never shows any sync for a requested one (e.g. it dropped the request silently). */
    var ideBegins = true

    /** The syncs started, in order; each waits for its deferred. */
    val started = mutableListOf<Pair<SyncUnit, CompletableDeferred<SyncResult>>>()

    /** Syncs cancelled by the coordinator (it stopped waiting for a result). */
    var cancelled = 0
        private set

    override suspend fun units(): List<SyncUnit> = units

    override suspend fun needsReload(): Boolean? = needsReload

    override fun activity(unit: SyncUnit): StateFlow<SyncActivity> = activities.getValue(unit.key)

    override suspend fun sync(unit: SyncUnit): SyncResult {
        val end = CompletableDeferred<SyncResult>()
        started += unit to end
        if (ideBegins) setBusy(unit, true)
        try {
            return end.await()
        } catch (e: CancellationException) {
            cancelled++
            throw e
        }
    }

    /** Ends the [index]th sync with [result]; the IDE becomes idle. */
    fun finish(index: Int, result: SyncResult = SyncResult.Succeeded()) {
        val (unit, end) = started[index]
        end.complete(result)
        setBusy(unit, false)
    }

    /** The IDE stops working on the [index]th sync without delivering its result. */
    fun goSilent(index: Int) = setBusy(started[index].first, false)

    /** The IDE starts or ends a sync of every unit by itself. */
    fun ideBusy(busy: Boolean) = units.forEach { setBusy(it, busy) }

    fun setBusy(unit: SyncUnit, busy: Boolean) {
        activities.getValue(unit.key).update {
            SyncActivity(busy, if (busy && !it.busy) it.starts + 1 else it.starts)
        }
    }
}

class FakeSyncEnvironment(vararg systems: BuildSystemSync) : SyncEnvironment {

    val systems = systems.toMutableList()
    var trusted = true
    val indexing = CompletableDeferred<Unit>()
    var indexingWaits = 0
        private set

    override fun isTrusted(): Boolean = trusted

    override suspend fun buildSystems(): List<BuildSystemSync> = systems

    override fun renderPath(clientPath: String): String = clientPath.removePrefix("/abs/").removePrefix("./")

    override suspend fun awaitIndexing() {
        indexingWaits++
        indexing.await()
    }
}

fun <T> ToolResult<T>.value(): T =
    (this as? ToolResult.Success)?.value ?: throw AssertionError("expected success: $this")

fun ToolResult<*>.code() = (this as? ToolResult.Failure)?.error?.code ?: throw AssertionError("expected failure: $this")
