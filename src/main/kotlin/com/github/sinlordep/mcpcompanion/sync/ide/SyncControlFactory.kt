package com.github.sinlordep.mcpcompanion.sync.ide

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.common.time.SystemClock
import com.github.sinlordep.mcpcompanion.sync.BuildSystemSync
import com.github.sinlordep.mcpcompanion.sync.SyncControl
import com.github.sinlordep.mcpcompanion.sync.SyncCoordinator
import com.github.sinlordep.mcpcompanion.sync.SyncEnvironment
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.observation.Observation
import kotlinx.coroutines.CoroutineScope

/** Wires `mc_sync` to the platform for [project]. Cheap; called once per tool call. */
fun syncControlFor(project: Project): SyncControl =
    SyncControl.create(IdeSyncEnvironment(project), SyncState.getInstance(project).coordinator, SystemClock)

/**
 * Per-project state of `mc_sync`: the coordinator that serialises syncs and remembers the latest one per linked
 * project. Its scope is the service's, cancelled when the project closes.
 */
@Service(Service.Level.PROJECT)
class SyncState(scope: CoroutineScope) {

    val coordinator: SyncCoordinator = SyncCoordinator(scope, SystemClock)

    companion object {
        fun getInstance(project: Project): SyncState = project.service()
    }
}

/** [SyncEnvironment] over the platform. */
internal class IdeSyncEnvironment(private val project: Project) : SyncEnvironment {

    override fun isTrusted(): Boolean = TrustedProjects.isProjectTrusted(project)

    override suspend fun buildSystems(): List<BuildSystemSync> =
        BuildSystemSyncProvider.EP.extensionList.flatMap { it.buildSystems(project) }.sortedBy { it.buildSystem.id }

    override fun renderPath(clientPath: String): String {
        val paths = ProjectFiles.of(project).paths
        return paths.render(paths.resolve(clientPath))
    }

    /**
     * "Done" means: the platform's activity trackers (Gradle and Maven register theirs) report no configuration in
     * progress, and then a smart read action ran, i.e. the indexes the import started were complete.
     */
    override suspend fun awaitIndexing() {
        Observation.awaitConfiguration(project)
        smartReadAction(project) { }
    }
}
