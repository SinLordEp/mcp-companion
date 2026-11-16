package com.github.sinlordep.mcpcompanion.sync

import kotlinx.coroutines.flow.StateFlow

/**
 * Gateway boundary: one build system's sync machinery in the IDE (strategy: Gradle and other external systems,
 * Maven). Never opens UI that waits for the user.
 */
interface BuildSystemSync {
    val buildSystem: BuildSystem

    /** The linked projects of this build system, as sync units. */
    suspend fun units(): List<SyncUnit>

    /**
     * Whether the IDE says this build system's files changed since the last sync (its "Load changes" notification);
     * null when it cannot tell.
     */
    suspend fun needsReload(): Boolean?

    /**
     * The IDE's own view of the syncs of [unit], whoever started them: [SyncActivity.busy] while the IDE reports a sync
     * of it in progress (every phase it reports, e.g. resolve and data import), [SyncActivity.starts] counts the syncs
     * it began. Emits on every change; event-driven. The coordinator trusts this, not its own record, to decide whether
     * a sync still runs.
     */
    fun activity(unit: SyncUnit): StateFlow<SyncActivity>

    /**
     * Starts a sync of [unit] and suspends until it ends. Cancellable: the coordinator cancels it when the IDE went
     * idle without reporting a result; cancelling never stops the IDE's sync itself.
     */
    suspend fun sync(unit: SyncUnit): SyncResult
}

/** What the IDE is doing for one sync unit: [busy] while it reports a sync in progress; [starts] syncs it began. */
data class SyncActivity(val busy: Boolean, val starts: Long) {
    companion object {
        val IDLE = SyncActivity(busy = false, starts = 0)
    }
}

/** Gateway boundary: the IDE project around the syncs. */
interface SyncEnvironment {
    /** False in safe mode: the IDE would not run the build scripts (Gradle) or would ask first. */
    fun isTrusted(): Boolean

    /** Every build system this IDE can sync, in a stable order; empty without Gradle, Maven or similar support. */
    suspend fun buildSystems(): List<BuildSystemSync>

    /** [clientPath] (relative to the project directory, `..` allowed, or absolute) in the form units render paths. */
    fun renderPath(clientPath: String): String

    /** Suspends until the IDE reports no configuration activity and its indexes are ready (smart mode). */
    suspend fun awaitIndexing()
}
