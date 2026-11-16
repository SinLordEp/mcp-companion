package com.github.sinlordep.mcpcompanion.sync.maven

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.sync.BuildSystem
import com.github.sinlordep.mcpcompanion.sync.BuildSystemSync
import com.github.sinlordep.mcpcompanion.sync.SyncActivity
import com.github.sinlordep.mcpcompanion.sync.SyncResult
import com.github.sinlordep.mcpcompanion.sync.SyncUnit
import com.github.sinlordep.mcpcompanion.sync.ide.BuildSystemSyncProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectNotificationAware
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.jetbrains.idea.maven.model.MavenProjectProblem
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.utils.MavenUtil

/** Registers [MavenSync]; loaded from `mcp-companion-maven.xml` only when the Maven plugin is present. */
class MavenSyncProvider : BuildSystemSyncProvider {
    override fun buildSystems(project: Project): List<BuildSystemSync> = listOf(MavenSync(project))
}

/**
 * Sync of all Maven projects of the IDE project: what the IDE's "Reload All Maven Projects" runs
 * (`MavenProjectsManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles`, background progress, no dialog). The
 * Maven plugin's API to sync single projects (`MavenAsyncProjectsManager`, `MavenSyncSpec`) is experimental in 263,
 * so the unit is all Maven projects. Start and end come from the application topic `MavenSyncListener`
 * ([MavenSyncProgress]); the result from the problems the Maven plugin records on its projects.
 */
internal class MavenSync(private val project: Project) : BuildSystemSync {

    override val buildSystem = BuildSystem(ID, MavenUtil.SYSTEM_ID.readableName)

    private val manager get() = MavenProjectsManager.getInstance(project)
    private val progress get() = MavenSyncProgress.getInstance(project)

    override suspend fun units(): List<SyncUnit> = readAction {
        if (!manager.isMavenizedProject) return@readAction emptyList()
        val paths = ProjectFiles.of(project).paths
        val roots = manager.rootProjects.map { paths.render(it.directory) }.sorted()
        listOf(SyncUnit(ID, buildSystem, roots, location = ""))
    }

    override suspend fun needsReload(): Boolean? =
        ExternalSystemProjectNotificationAware.getInstance(project).isNotificationVisible(MavenUtil.SYSTEM_ID)

    override fun activity(unit: SyncUnit): StateFlow<SyncActivity> = progress.activity

    override suspend fun sync(unit: SyncUnit): SyncResult {
        val before = progress.finished.value
        withContext(Dispatchers.EDT) { manager.forceUpdateAllProjectsOrFindAllAvailablePomFiles() }
        // The coordinator waited until no sync ran, so the first sync that finishes now is ours.
        progress.finished.first { it > before }
        return readAction { resultOf(manager.projects.flatMap { it.problems }) }
    }

    private fun resultOf(problems: List<MavenProjectProblem>): SyncResult {
        val messages = problems.map { "${it.path}: ${it.description}" }.distinct()
        val fatal = problems.filter { it.type in FATAL }
        return if (fatal.isEmpty()) {
            SyncResult.Succeeded(messages.take(MAX_MESSAGES))
        } else {
            val message = "${fatal.first().path}: ${fatal.first().description}"
            val others = messages.filterNot { it == message }.take(MAX_MESSAGES)
            SyncResult.Failed(message, others.joinToString("\n").ifEmpty { null })
        }
    }

    private companion object {
        const val ID = "maven"
        const val MAX_MESSAGES = 20

        /** Problems that mean a POM could not be read at all; the others (e.g. unresolved artifacts) are reported. */
        val FATAL = setOf(MavenProjectProblem.ProblemType.SYNTAX, MavenProjectProblem.ProblemType.STRUCTURE)
    }
}
