package com.github.sinlordep.mcpcompanion.common.modulegraph.ide

import com.github.sinlordep.mcpcompanion.common.modulegraph.Dependency
import com.github.sinlordep.mcpcompanion.common.modulegraph.DependencyScope
import com.github.sinlordep.mcpcompanion.common.modulegraph.DependencyTarget
import com.github.sinlordep.mcpcompanion.common.modulegraph.ProjectGraph
import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEntry
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.DependencyScope as IdeScope

/**
 * The declared dependencies of [project]'s modules (`ModuleManager`, `ModuleRootManager` order entries), read in one
 * cancellable read action, so the answer is one consistent snapshot. The module model is not indexed: no smart mode.
 * Shared by every feature that needs the module graph (`mc_modules`, `mc_build`, `mc_diagnostics`).
 *
 * [coordinates] gives a library's `group:artifact:version` when a language plugin knows it.
 */
class IdeModuleGraph(private val project: Project, private val coordinates: (Library) -> String? = { null }) {

    suspend fun read(includeLibraries: Boolean): ProjectGraph = readAction {
        val modules = ModuleManager.getInstance(project).modules
        val dependencies = modules.flatMap { module ->
            ProgressManager.checkCanceled()
            ModuleRootManager.getInstance(module).orderEntries.mapNotNull {
                dependencyOf(module.name, it, includeLibraries)
            }
        }
        ProjectGraph(modules.map { it.name }.toSet(), dependencies)
    }

    private fun dependencyOf(from: String, entry: OrderEntry, includeLibraries: Boolean): Dependency? = when {
        entry is ModuleOrderEntry -> Dependency(
            from = from,
            target = DependencyTarget.Module(entry.moduleName, missing = entry.module == null),
            scope = scopeOf(entry.scope),
            exported = entry.isExported,
        )

        entry is LibraryOrderEntry && includeLibraries -> Dependency(
            from = from,
            target = DependencyTarget.Library(
                name = entry.libraryName ?: entry.presentableName,
                coordinates = entry.library?.let(coordinates),
            ),
            scope = scopeOf(entry.scope),
            exported = entry.isExported,
        )

        else -> null
    }

    private fun scopeOf(scope: IdeScope): DependencyScope = when (scope) {
        IdeScope.COMPILE -> DependencyScope.COMPILE
        IdeScope.TEST -> DependencyScope.TEST
        IdeScope.RUNTIME -> DependencyScope.RUNTIME
        IdeScope.PROVIDED -> DependencyScope.PROVIDED
    }
}
