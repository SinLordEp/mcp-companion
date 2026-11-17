package com.github.sinlordep.mcpcompanion.toolsets.sync

import com.github.sinlordep.mcpcompanion.sync.SyncAction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** MCP input values of `action`. */
@Serializable
enum class SyncActionInput(val action: SyncAction) {
    @SerialName("status")
    STATUS(SyncAction.STATUS),

    @SerialName("sync")
    SYNC(SyncAction.SYNC),
}
