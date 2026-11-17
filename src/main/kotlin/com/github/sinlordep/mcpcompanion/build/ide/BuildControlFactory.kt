package com.github.sinlordep.mcpcompanion.build.ide

import com.github.sinlordep.mcpcompanion.build.BuildControl
import com.github.sinlordep.mcpcompanion.build.BuildCoordinator
import com.github.sinlordep.mcpcompanion.build.BuildEnvironment
import com.github.sinlordep.mcpcompanion.build.ModuleBuilder
import com.github.sinlordep.mcpcompanion.common.modulegraph.ProjectGraph
import com.github.sinlordep.mcpcompanion.common.modulegraph.ide.IdeModuleGraph
import com.github.sinlordep.mcpcompanion.common.time.SystemClock
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

/** Wires `mc_build` to the platform for [project]. Cheap; called once per tool call. */
fun buildControlFor(project: Project): BuildControl =
    BuildControl.create(IdeBuildEnvironment(project), BuildState.getInstance(project).coordinator, SystemClock)

/**
 * Per-project state of `mc_build`: the coordinator that allows one build at a time and remembers the latest one. Its
 * scope is the service's, cancelled when the project closes.
 */
@Service(Service.Level.PROJECT)
class BuildState(scope: CoroutineScope) {

    val coordinator: BuildCoordinator = BuildCoordinator(scope, SystemClock)

    companion object {
        fun getInstance(project: Project): BuildState = project.service()
    }
}

/** [BuildEnvironment] over the platform. */
internal class IdeBuildEnvironment(private val project: Project) : BuildEnvironment {

    override fun isTrusted(): Boolean = TrustedProjects.isProjectTrusted(project)

    override suspend fun moduleGraph(): ProjectGraph = IdeModuleGraph(project).read(includeLibraries = false)

    override fun builder(): ModuleBuilder? = ModuleBuilderProvider.EP.extensionList.firstOrNull()?.builderFor(project)
}
