package com.github.sinlordep.mcpcompanion.sync.externalsystem

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.sync.BuildSystem
import com.github.sinlordep.mcpcompanion.sync.BuildSystemSync
import com.github.sinlordep.mcpcompanion.sync.SyncActivity
import com.github.sinlordep.mcpcompanion.sync.SyncResult
import com.github.sinlordep.mcpcompanion.sync.SyncUnit
import com.github.sinlordep.mcpcompanion.sync.ide.BuildSystemSyncProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.externalSystem.ExternalSystemManager
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectNotificationAware
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Registers one [ExternalSystemSync] per external system of the IDE (Gradle, and e.g. sbt where installed). */
class ExternalSystemSyncProvider : BuildSystemSyncProvider {
    override fun buildSystems(project: Project): List<BuildSystemSync> =
        ExternalSystemManager.EP_NAME.extensionList.map { ExternalSystemSync(project, it.systemId) }
}

/**
 * Sync of the linked projects of one external system through the platform's build-system-neutral API; Gradle needs no
 * class of the Gradle plugin. A unit is one linked project (`externalProjectPath` of the system's settings).
 *
 * The sync is what the IDE's "Reload Gradle Project" runs: `ExternalSystemUtil.refreshProject` in background mode,
 * with the rules of docs/notes-step4.md that keep it free of dialogs (no data-selection dialog, no tool window
 * activation, no navigation to an error). Its end is learnt from the import callback, and, for the ends that bypass
 * the callback, from the task's cancel event, the data import's failure event and the failure of a before-sync task
 * ([SyncEnd]). Whatever ends silently is caught by the coordinator's watch over [activity].
 */
internal class ExternalSystemSync(private val project: Project, private val systemId: ProjectSystemId) :
    BuildSystemSync {

    override val buildSystem = BuildSystem(systemId.id.lowercase(), systemId.readableName)

    override suspend fun units(): List<SyncUnit> {
        val paths = ProjectFiles.of(project).paths
        return ExternalSystemApiUtil.getSettings(project, systemId).linkedProjectsSettings
            .map { FileUtil.toSystemIndependentName(it.externalProjectPath) }
            .sorted()
            .map { path -> SyncUnit("${systemId.id}:$path", buildSystem, listOf(paths.render(path)), path) }
    }

    override suspend fun needsReload(): Boolean? =
        ExternalSystemProjectNotificationAware.getInstance(project).isNotificationVisible(systemId)

    override fun activity(unit: SyncUnit): StateFlow<SyncActivity> =
        ExternalSystemSyncActivity.getInstance(project).of(systemId, unit.location)

    override suspend fun sync(unit: SyncUnit): SyncResult {
        val lifetime = Disposer.newDisposable("mc_sync ${unit.key}")
        try {
            val end = SyncEnd(project, systemId, unit.location).also { it.listen(lifetime) }
            val spec = ImportSpecBuilder(project, systemId)
                .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
                .withSelectProjectDataToImport(false)
                .withActivateToolWindowOnStart(false)
                .withActivateToolWindowOnFailure(false)
                .dontNavigateToError()
                .withCallback(end.callback)
                .build()
            // refreshProject asserts a write-safe context and saves all documents first, as the IDE's own action does.
            withContext(Dispatchers.EDT) { ExternalSystemUtil.refreshProject(unit.location, spec) }
            return end.result.await()
        } finally {
            Disposer.dispose(lifetime)
        }
    }
}
