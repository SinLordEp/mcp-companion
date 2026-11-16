package com.github.sinlordep.mcpcompanion.sync

import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.map

/**
 * `status`: the build systems this IDE can sync, their linked projects, whether the IDE says they need a reload,
 * whether a sync runs, and the latest sync `mc_sync` started per project. Read-only; empty without build system
 * support.
 */
class ReportStatus(private val selection: UnitSelection, private val coordinator: SyncCoordinator) :
    SyncActionHandler {

    override val action = SyncAction.STATUS
    override val parameters = setOf(SyncParameter.BUILD_SYSTEM, SyncParameter.PROJECT)

    override suspend fun handle(request: SyncArguments): ToolResult<SyncOutcome> =
        selection.select(request).map { selected ->
            SyncOutcome.Status(
                selected.systems.map { (sync, units) ->
                    BuildSystemStatus(sync.buildSystem, sync.needsReload(), units.map { status(sync, it) })
                },
            )
        }

    private fun status(sync: BuildSystemSync, unit: SyncUnit): UnitStatus {
        val latest = coordinator.latest(unit.key)
        return UnitStatus(
            unit = unit,
            running = latest?.isRunning == true || sync.activity(unit).value.busy,
            last = latest?.let { LastSync(it.startedAt, it.finished) },
        )
    }
}
