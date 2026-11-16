package com.github.sinlordep.mcpcompanion.sync

import com.github.sinlordep.mcpcompanion.common.actions.ToolArguments

/** The actions of `mc_sync`. */
enum class SyncAction { STATUS, SYNC }

/** The optional arguments of `mc_sync`; each action accepts a subset. */
enum class SyncParameter(val label: String) {
    BUILD_SYSTEM("buildSystem"),
    PROJECT("project"),
    TIMEOUT("timeoutSeconds"),
    WAIT_FOR_INDEXING("waitForIndexing"),
}

/** Raw client arguments of one `mc_sync` call, not yet validated. Null means "not given". */
data class SyncArguments(
    val buildSystem: String? = null,
    val project: String? = null,
    val timeoutSeconds: Int? = null,
    val waitForIndexing: Boolean? = null,
) : ToolArguments<SyncParameter> {

    override fun given(): Set<SyncParameter> = buildSet {
        if (buildSystem != null) add(SyncParameter.BUILD_SYSTEM)
        if (project != null) add(SyncParameter.PROJECT)
        if (timeoutSeconds != null) add(SyncParameter.TIMEOUT)
        if (waitForIndexing != null) add(SyncParameter.WAIT_FOR_INDEXING)
    }
}
