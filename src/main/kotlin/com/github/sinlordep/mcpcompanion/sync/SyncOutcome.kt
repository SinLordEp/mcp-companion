package com.github.sinlordep.mcpcompanion.sync

import java.time.Instant

/** What an `mc_sync` action produced. */
sealed interface SyncOutcome {

    data class Status(val buildSystems: List<BuildSystemStatus>) : SyncOutcome

    /** [waitedMillis]: how long the call waited in total. */
    data class Synced(val units: List<UnitSync>, val indexing: IndexingWait, val waitedMillis: Long) : SyncOutcome
}

/** [needsReload]: null when the IDE cannot tell. */
data class BuildSystemStatus(val buildSystem: BuildSystem, val needsReload: Boolean?, val units: List<UnitStatus>)

/** [running]: a sync of the unit runs now, whoever started it. [last]: the latest sync `mc_sync` started, if any. */
data class UnitStatus(val unit: SyncUnit, val running: Boolean, val last: LastSync?)

/** The latest sync `mc_sync` started for a unit: running ([record] null) or finished. */
data class LastSync(val startedAt: Instant, val record: SyncRecord?)

/** How the sync of one unit went within one call. */
data class UnitSync(val unit: SyncUnit, val state: UnitSyncState, val joined: Boolean, val run: LastSync?)

enum class UnitSyncState {
    /** The sync ended; see its result. */
    FINISHED,

    /** The timeout passed while the sync ran; it goes on, and a later `status` shows its result. */
    RUNNING,

    /** The timeout passed while another sync of the unit ran; this call started nothing. */
    NOT_STARTED,
}

/** Whether the call waited for indexing after the syncs. */
enum class IndexingWait {
    /** waitForIndexing was not asked for. */
    NOT_REQUESTED,

    /** The IDE reported no configuration activity and was in smart mode after the syncs. */
    DONE,

    /** The timeout passed while the IDE was still configuring or indexing. */
    TIMED_OUT,

    /** Not every sync finished successfully, so the call did not wait. */
    SKIPPED,
}
