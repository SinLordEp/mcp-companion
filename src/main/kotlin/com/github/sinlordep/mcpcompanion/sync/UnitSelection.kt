package com.github.sinlordep.mcpcompanion.sync

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/** The build systems and their units a call is about, after the `buildSystem` and `project` filters. */
data class Selection(val systems: List<SelectedSystem>) {
    val units: List<Pair<BuildSystemSync, SyncUnit>> get() = systems.flatMap { s -> s.units.map { s.sync to it } }
}

data class SelectedSystem(val sync: BuildSystemSync, val units: List<SyncUnit>)

/** Applies the `buildSystem` and `project` filters of a call to what the IDE has. */
class UnitSelection(private val environment: SyncEnvironment) {

    suspend fun select(arguments: SyncArguments): ToolResult<Selection> {
        val all = environment.buildSystems()
        val wanted = arguments.buildSystem?.trim()
        val systems = if (wanted == null) all else all.filter { it.buildSystem.id.equals(wanted, ignoreCase = true) }
        if (wanted != null && systems.isEmpty()) {
            return ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "This IDE cannot sync '$wanted' projects.",
                all.map { it.buildSystem.id }.ifEmpty { listOf("none") }.joinToString(prefix = "Build systems: "),
            )
        }
        val project = arguments.project?.let(environment::renderPath)
        val selected = systems.map { sync ->
            val units = sync.units()
            SelectedSystem(sync, if (project == null) units else units.filter { project in it.projects })
        }
        if (project != null && selected.all { it.units.isEmpty() }) {
            return ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "No linked build project is at '$project'.",
                "action=status lists the linked projects and their paths.",
            )
        }
        val nonEmpty = if (project == null) selected else selected.filter { it.units.isNotEmpty() }
        return ToolResult.success(Selection(nonEmpty))
    }
}
