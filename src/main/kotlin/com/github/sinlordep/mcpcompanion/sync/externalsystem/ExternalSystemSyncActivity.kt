package com.github.sinlordep.mcpcompanion.sync.externalsystem

import com.github.sinlordep.mcpcompanion.sync.SyncActivity
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The IDE's syncs of external-system projects (Gradle and others), whoever started them, per linked project: busy
 * from a resolve task's start event to its end event and from the data import's start to its end or failure (the
 * two phases the IDE reports), and the number of resolves begun. Fed by the public task notifications and the
 * `ProjectDataImportListener` topic from the first `mc_sync` call on; a resolve that already ran then is taken from
 * the processing manager.
 */
@Service(Service.Level.PROJECT)
class ExternalSystemSyncActivity(project: Project) : Disposable {

    private data class Key(val systemId: ProjectSystemId, val path: String)

    private class Counts(var resolving: Int, var starts: Long, val flow: MutableStateFlow<SyncActivity>)

    private val lock = Any()
    private val counts = HashMap<Key, Counts>()
    private val importing = HashMap<String, Int>()

    init {
        val tasks = object : ExternalSystemTaskNotificationListener {
            override fun onStart(projectPath: String, id: ExternalSystemTaskId) {
                if (id.type == ExternalSystemTaskType.RESOLVE_PROJECT) {
                    update(Key(id.projectSystemId, normal(projectPath))) {
                        resolving++
                        starts++
                    }
                }
            }

            override fun onEnd(projectPath: String, id: ExternalSystemTaskId) {
                if (id.type == ExternalSystemTaskType.RESOLVE_PROJECT) {
                    update(Key(id.projectSystemId, normal(projectPath))) {
                        resolving = (resolving - 1).coerceAtLeast(0)
                    }
                }
            }
        }
        ExternalSystemProgressNotificationManager.getInstance().addNotificationListener(tasks, this)
        project.messageBus.connect(this).subscribe(
            ProjectDataImportListener.TOPIC,
            object : ProjectDataImportListener {
                override fun onImportStarted(projectPath: String?) = importChanged(projectPath, +1)

                override fun onImportFinished(projectPath: String?) = importChanged(projectPath, -1)

                override fun onImportFailed(projectPath: String?, t: Throwable) = importChanged(projectPath, -1)
            },
        )
    }

    fun of(systemId: ProjectSystemId, path: String): StateFlow<SyncActivity> = synchronized(lock) {
        countsOf(Key(systemId, normal(path))).flow
    }

    private fun countsOf(key: Key): Counts = counts.getOrPut(key) {
        val running = ExternalSystemProcessingManager.getInstance()
            .findTask(ExternalSystemTaskType.RESOLVE_PROJECT, key.systemId, key.path) != null
        val initial = if (running) 1 else 0
        Counts(initial, 0, MutableStateFlow(SyncActivity(running || (importing[key.path] ?: 0) > 0, 0)))
    }

    private fun update(key: Key, change: Counts.() -> Unit) = synchronized(lock) {
        countsOf(key).apply(change).publish(key.path)
    }

    private fun importChanged(projectPath: String?, delta: Int) {
        if (projectPath == null) return
        val path = normal(projectPath)
        synchronized(lock) {
            importing[path] = ((importing[path] ?: 0) + delta).coerceAtLeast(0)
            counts.filterKeys { it.path == path }.forEach { (key, value) -> value.publish(key.path) }
        }
    }

    private fun Counts.publish(path: String) {
        flow.value = SyncActivity(busy = resolving > 0 || (importing[path] ?: 0) > 0, starts = starts)
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): ExternalSystemSyncActivity = project.service()

        private fun normal(path: String): String = FileUtil.toSystemIndependentName(path)
    }
}
