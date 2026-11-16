package com.github.sinlordep.mcpcompanion.toolsets.modules

import com.github.sinlordep.mcpcompanion.common.paging.Page
import com.github.sinlordep.mcpcompanion.modules.ContentRoot
import com.github.sinlordep.mcpcompanion.modules.Dependency
import com.github.sinlordep.mcpcompanion.modules.DependencyCycle
import com.github.sinlordep.mcpcompanion.modules.DependencyTarget
import com.github.sinlordep.mcpcompanion.modules.GraphEdge
import com.github.sinlordep.mcpcompanion.modules.ModuleDescription
import com.github.sinlordep.mcpcompanion.modules.ModulesOutcome
import com.github.sinlordep.mcpcompanion.modules.SourceFolder
import kotlinx.serialization.Serializable

/**
 * MCP output model of `mc_modules`: the action's list (modules, edges or cycles) and the paging fields. total counts
 * the items of all pages; continue with offset=nextOffset while hasMore.
 */
@Serializable
data class ModulesOutput(
    val modules: List<ModuleOutput>? = null,
    /** graph: the walk's root and direction, and how many modules it reached (root included). */
    val root: String? = null,
    val direction: String? = null,
    val reached: Int? = null,
    val edges: List<EdgeOutput>? = null,
    val cycles: List<CycleOutput>? = null,
    val ignoredTestScope: Boolean? = null,
    val total: Int,
    val offset: Int,
    val hasMore: Boolean,
    val nextOffset: Int? = null,
) {
    companion object {
        fun from(outcome: ModulesOutcome): ModulesOutput = when (outcome) {
            is ModulesOutcome.Modules ->
                paged(outcome.page, outcome.total).copy(modules = outcome.page.items.map(ModuleOutput::from))

            is ModulesOutcome.Graph -> paged(outcome.page, outcome.total).copy(
                root = outcome.root,
                direction = outcome.direction?.name?.lowercase(),
                reached = outcome.reached,
                edges = outcome.page.items.map(EdgeOutput::from),
            )

            is ModulesOutcome.Cycles -> paged(outcome.page, outcome.total).copy(
                cycles = outcome.page.items.map(CycleOutput::from),
                ignoredTestScope = outcome.ignoredTestScope,
            )
        }

        private fun paged(page: Page<*>, total: Int) =
            ModulesOutput(total = total, offset = page.offset, hasMore = page.hasMore, nextOffset = page.nextOffset)
    }
}

@Serializable
data class ModuleOutput(
    val name: String,
    val type: String? = null,
    /** gradle, maven, ... when a build system imported the module. */
    val buildSystem: String? = null,
    val sdk: String? = null,
    val languageLevel: String? = null,
    val contentRoots: List<ContentRootOutput>,
) {
    companion object {
        fun from(module: ModuleDescription) = ModuleOutput(
            name = module.name,
            type = module.type,
            buildSystem = module.buildSystem,
            sdk = module.sdk,
            languageLevel = module.languageLevel,
            contentRoots = module.contentRoots.map(ContentRootOutput::from),
        )
    }
}

/** path: as read_file takes it; outsideProjectDir: true for a root next to the project directory (`../x`). */
@Serializable
data class ContentRootOutput(
    val path: String,
    val outsideProjectDir: Boolean? = null,
    val folders: List<FolderOutput>,
    val omittedFolders: Int? = null,
) {
    companion object {
        fun from(root: ContentRoot) = ContentRootOutput(
            path = root.path,
            outsideProjectDir = root.outsideProjectDir.takeIf { it },
            folders = root.folders.map(FolderOutput::from),
            omittedFolders = root.omittedFolders.takeIf { it > 0 },
        )
    }
}

/** kind: source, test, resource, test_resource or excluded. */
@Serializable
data class FolderOutput(val path: String, val kind: String, val generated: Boolean? = null) {
    companion object {
        fun from(folder: SourceFolder) =
            FolderOutput(folder.path, folder.kind.name.lowercase(), folder.generated.takeIf { it })
    }
}

/**
 * from depends on to. kind: module or library. scope: compile, test, runtime or provided. depth: edges from the walk's
 * root. missing: the module does not exist. coordinates: group:artifact:version of a library, when known.
 */
@Serializable
data class EdgeOutput(
    val from: String,
    val to: String,
    val kind: String,
    val scope: String,
    val exported: Boolean,
    val depth: Int? = null,
    val missing: Boolean? = null,
    val coordinates: String? = null,
) {
    companion object {
        fun from(edge: GraphEdge) = from(edge.dependency).copy(depth = edge.depth)

        fun from(dependency: Dependency): EdgeOutput {
            val target = dependency.target
            return EdgeOutput(
                from = dependency.from,
                to = target.name,
                kind = if (target is DependencyTarget.Library) "library" else "module",
                scope = dependency.scope.name.lowercase(),
                exported = dependency.exported,
                missing = (target as? DependencyTarget.Module)?.missing?.takeIf { it },
                coordinates = (target as? DependencyTarget.Library)?.coordinates,
            )
        }
    }
}

/** modules: the group; cycle: one shortest closed path through its first module; edges: all edges within it. */
@Serializable
data class CycleOutput(
    val modules: List<String>,
    val cycle: List<String>,
    val edges: List<EdgeOutput>,
    val omittedEdges: Int? = null,
) {
    companion object {
        fun from(cycle: DependencyCycle) = CycleOutput(
            modules = cycle.modules,
            cycle = cycle.cycle,
            edges = cycle.edges.map(EdgeOutput::from),
            omittedEdges = cycle.omittedEdges.takeIf { it > 0 },
        )
    }
}
