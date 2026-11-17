package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/**
 * Finds the process an action is about: by `process` handle, or by `configuration` (and `type`), which selects that
 * configuration's most recently started process.
 */
class ProcessResolver(private val catalog: ProcessCatalog) {

    /** The process [arguments] name; with [aliveOnly], a configuration must have exactly one alive process. */
    suspend fun resolve(arguments: RunArguments, aliveOnly: Boolean = false): ToolResult<TrackedProcess> {
        val handle = arguments.process
        val configuration = arguments.configuration
        return when {
            handle != null && configuration != null -> ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "Give either process or configuration, not both.",
                "A process handle already names one run of one configuration.",
            )

            handle != null -> byHandle(handle, arguments.type)

            configuration != null -> byConfiguration(configuration, arguments.type, aliveOnly)

            else -> ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "This action needs process or configuration.",
                "Pass process (a handle from action=processes or start) or the run configuration's name.",
            )
        }
    }

    private suspend fun byHandle(handle: String, type: String?): ToolResult<TrackedProcess> {
        if (type != null) {
            return ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "type only applies together with configuration.",
                "Omit type when passing process.",
            )
        }
        val id = ProcessId.parse(handle) ?: return ToolResult.failure(
            ErrorCode.INVALID_ARGUMENT,
            "'$handle' is not a process handle; handles look like p1, p2, ...",
            "Use a handle from action=processes or start.",
        )
        val process = catalog.find(id) ?: return ToolResult.failure(
            ErrorCode.NOT_FOUND,
            "No process $id: it was released (its run tab was closed or it was among the oldest ended processes), " +
                "or it belongs to another project or IDE session.",
            "Call action=processes to see the current handles.",
        )
        return ToolResult.success(process)
    }

    private suspend fun byConfiguration(name: String, type: String?, aliveOnly: Boolean): ToolResult<TrackedProcess> {
        val candidates = catalog.processes().filter { process ->
            val snapshot = process.snapshot
            val configuration = snapshot.configuration
            (configuration?.name ?: snapshot.name) == name && (type == null || configuration?.hasType(type) == true)
        }
        val alive = candidates.filter { it.snapshot.state.isAlive }
        return when {
            candidates.isEmpty() -> ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "No process of configuration '$name'${type?.let { " (type $it)" }.orEmpty()} is known.",
                "Start it with action=start, or call action=processes.",
            )

            aliveOnly && alive.size > 1 -> ToolResult.failure(
                ErrorCode.AMBIGUOUS,
                "'$name' runs ${alive.size} times: ${alive.joinToString { it.id.value }}.",
                "Pass process with one of these handles.",
            )

            else -> ToolResult.success(alive.firstOrNull() ?: candidates.first())
        }
    }
}

/** Finds the run configuration `start` means: by name, and by `type` when several types use the name. */
class ConfigurationResolver(private val execution: ExecutionGateway) {

    suspend fun resolve(name: String?, type: String?): ToolResult<ConfigurationRef> {
        if (name.isNullOrBlank()) {
            return ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "action=start needs configuration, the name of an existing run configuration.",
                "List the names with the official get_run_configurations tool.",
            )
        }
        val named = execution.configurationsNamed(name)
        val matching = if (type == null) named else named.filter { it.hasType(type) }
        return when {
            matching.size == 1 -> ToolResult.success(matching.single())

            named.isEmpty() -> ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "There is no run configuration named '$name'.",
                "Names are case-sensitive; list them with the official get_run_configurations tool.",
            )

            matching.isEmpty() -> ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "No run configuration '$name' has type '$type'. Its types: ${types(named)}.",
                "Pass one of these as type.",
            )

            else -> ToolResult.failure(
                ErrorCode.AMBIGUOUS,
                "${matching.size} run configurations are named '$name', of types ${types(matching)}.",
                "Pass type with one of these type ids; a name is unique within its type.",
            )
        }
    }

    private fun types(configurations: List<ConfigurationRef>): String = configurations.joinToString {
        "${it.typeId} (${it.typeName}${it.folder?.let { f -> ", folder $f" }.orEmpty()})"
    }
}

/** Whether [type] names this configuration's type, by id or display name, ignoring case. */
fun ConfigurationRef.hasType(type: String): Boolean =
    typeId.equals(type.trim(), ignoreCase = true) || typeName.equals(type.trim(), ignoreCase = true)
