package com.github.sinlordep.mcpcompanion.sync

import java.time.Instant

/** A build system the IDE imports projects from. [id] is what clients write (`gradle`, `maven`). */
data class BuildSystem(val id: String, val name: String)

/**
 * Linked build projects that the IDE syncs in one go: one linked Gradle (or other external-system) project, or all
 * Maven projects of the IDE project (the Maven plugin has no public API to sync one root project alone).
 *
 * [key] identifies the unit across calls; [projects] are the projects' directories rendered like every location of the
 * bundled MCP Server; [location] is the gateway's own address of the unit, opaque to the use cases.
 */
data class SyncUnit(val key: String, val buildSystem: BuildSystem, val projects: List<String>, val location: String)

/** How one sync ended. */
sealed interface SyncResult {
    /** [messages]: problems the build tool reported without failing the sync (bounded). */
    data class Succeeded(val messages: List<String> = emptyList()) : SyncResult

    /** [message]: the build tool's reason; [details]: more of it (e.g. the cause chain), bounded. */
    data class Failed(val message: String, val details: String? = null) : SyncResult

    /** The sync was cancelled (by the user or the IDE) before it finished. */
    data object Cancelled : SyncResult
}

/** A finished sync. */
data class SyncRecord(val result: SyncResult, val startedAt: Instant, val endedAt: Instant)
