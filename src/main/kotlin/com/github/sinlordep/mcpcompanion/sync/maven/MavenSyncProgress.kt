package com.github.sinlordep.mcpcompanion.sync.maven

import com.github.sinlordep.mcpcompanion.sync.SyncActivity
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.idea.maven.project.MavenSyncListener

/**
 * How many Maven syncs of one project started and finished since the plugin was loaded. Fed by [MavenSyncEvents];
 * a sync that was already running when the plugin loaded is not seen.
 */
@Service(Service.Level.PROJECT)
class MavenSyncProgress {

    private val lock = Any()
    private var started = 0L
    private val activityFlow = MutableStateFlow(SyncActivity.IDLE)
    private val finishedFlow = MutableStateFlow(0L)

    /** Busy between a sync's start and its end; starts counts the syncs begun. */
    val activity: StateFlow<SyncActivity> get() = activityFlow

    /** The number of syncs that finished. */
    val finished: StateFlow<Long> get() = finishedFlow

    internal fun started() = synchronized(lock) {
        started++
        publish()
    }

    internal fun finished() = synchronized(lock) {
        finishedFlow.value++
        publish()
    }

    private fun publish() {
        activityFlow.value = SyncActivity(busy = started > finishedFlow.value, starts = started)
    }

    companion object {
        fun getInstance(project: Project): MavenSyncProgress = project.service()
    }
}

/** Application listener on the Maven plugin's public sync topic (`mcp-companion-maven.xml`). */
class MavenSyncEvents : MavenSyncListener {
    override fun syncStarted(project: Project) {
        LOG.info("Maven sync started: ${project.name}")
        if (!project.isDisposed) MavenSyncProgress.getInstance(project).started()
    }

    override fun syncFinished(project: Project) {
        LOG.info("Maven sync finished: ${project.name}")
        if (!project.isDisposed) MavenSyncProgress.getInstance(project).finished()
    }

    private companion object {
        val LOG = logger<MavenSyncEvents>()
    }
}
