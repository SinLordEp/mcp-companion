package com.github.sinlordep.mcpcompanion.toolsets

import com.github.sinlordep.mcpcompanion.status.IdeStatus
import com.github.sinlordep.mcpcompanion.yield.ToolsetState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** MCP output model of `mc_status`. */
@Serializable
data class StatusOutput(
    val pluginVersion: String?,
    val ideBuild: String,
    val projectName: String,
    val toolsets: List<ToolsetStateOutput>,
) {
    companion object {
        fun from(status: IdeStatus, toolsets: List<ToolsetOverview>): StatusOutput = StatusOutput(
            pluginVersion = status.pluginVersion,
            ideBuild = status.ideBuild,
            projectName = status.projectName,
            toolsets = toolsets.map(ToolsetStateOutput::from),
        )
    }
}

/** One toolset in [StatusOutput]. [officialTools] is empty unless [state] is [StateName.YIELDED]. */
@Serializable
data class ToolsetStateOutput(
    val id: String,
    val name: String,
    val state: StateName,
    val officialTools: List<String>,
) {
    @Serializable
    enum class StateName {
        @SerialName("available")
        AVAILABLE,

        @SerialName("yielded")
        YIELDED,
    }

    companion object {
        fun from(overview: ToolsetOverview): ToolsetStateOutput = when (val state = overview.state) {
            ToolsetState.Available -> ToolsetStateOutput(
                overview.id,
                overview.displayName,
                StateName.AVAILABLE,
                emptyList(),
            )

            is ToolsetState.Yielded ->
                ToolsetStateOutput(overview.id, overview.displayName, StateName.YIELDED, state.officialTools.sorted())
        }
    }
}
