package com.github.sinlordep.mcpcompanion.modules.ide

import com.github.sinlordep.mcpcompanion.common.modulegraph.ProjectGraph
import com.github.sinlordep.mcpcompanion.common.modulegraph.ide.IdeModuleGraph
import com.github.sinlordep.mcpcompanion.common.paths.ProjectPathRenderer
import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.modules.ContentRoot
import com.github.sinlordep.mcpcompanion.modules.ModuleDescription
import com.github.sinlordep.mcpcompanion.modules.ModulesControl
import com.github.sinlordep.mcpcompanion.modules.ProjectModelGateway
import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtilCore

/** Wires `mc_modules` to the platform for [project]. Cheap; called once per tool call. */
fun modulesControlFor(project: Project): ModulesControl =
    ModulesControl.create(IdeProjectModelGateway(project) { ModuleFactsProvider.EP.extensionList })

/**
 * [ProjectModelGateway] over the module model (`ModuleManager`, `ModuleRootManager`; the graph through the shared
 * [IdeModuleGraph]). Each call reads in one cancellable read action (no smart mode needed: the module model is not
 * indexed), so its answer is consistent.
 */
internal class IdeProjectModelGateway(
    private val project: Project,
    private val facts: () -> List<ModuleFactsProvider>,
) : ProjectModelGateway {

    override suspend fun modules(): List<ModuleDescription> = readAction {
        val paths = ProjectFiles.of(project).paths
        val providers = facts()
        ModuleManager.getInstance(project).modules.map { module ->
            ProgressManager.checkCanceled()
            describe(module, paths, providers)
        }
    }

    override suspend fun graph(includeLibraries: Boolean): ProjectGraph {
        val providers = facts()
        return IdeModuleGraph(project) { library -> providers.firstNotNullOfOrNull { it.coordinates(library) } }
            .read(includeLibraries)
    }

    private fun describe(
        module: Module,
        paths: ProjectPathRenderer,
        providers: List<ModuleFactsProvider>,
    ): ModuleDescription {
        val roots = ModuleRootManager.getInstance(module)
        return ModuleDescription(
            name = module.name,
            type = ModuleType.get(module).id,
            buildSystem = providers.firstNotNullOfOrNull { it.buildSystem(module) },
            sdk = roots.sdk?.name,
            languageLevel = providers.firstNotNullOfOrNull { it.languageLevel(module) },
            contentRoots = roots.contentEntries.map { contentRoot(it, paths) },
        )
    }

    private fun contentRoot(entry: ContentEntry, paths: ProjectPathRenderer): ContentRoot {
        val absolute = VfsUtilCore.urlToPath(entry.url)
        val folders = ContentFolders.of(entry) { url -> paths.render(VfsUtilCore.urlToPath(url)) }
        return ContentRoot(
            path = paths.render(absolute),
            outsideProjectDir = paths.relativize(absolute) == null,
            folders = folders.take(ContentFolders.MAX_PER_ROOT),
            omittedFolders = (folders.size - ContentFolders.MAX_PER_ROOT).coerceAtLeast(0),
        )
    }
}
