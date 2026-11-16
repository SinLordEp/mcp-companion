package com.github.sinlordep.mcpcompanion.status

/** Snapshot of the environment the plugin runs in. Domain model; not serialized directly. */
data class IdeStatus(val pluginVersion: String?, val ideBuild: String, val projectName: String)
