package com.github.sinlordep.mcpcompanion.modules

/** A module of the project model with where its files are. */
data class ModuleDescription(
    val name: String,
    /** The IDE's module type id, e.g. `JAVA_MODULE`. */
    val type: String?,
    /** The build system that owns the module (`gradle`, `maven`, ...); null when the IDE knows none. */
    val buildSystem: String?,
    val sdk: String?,
    /** The effective language level, when a language plugin reports one (Java: `21`). */
    val languageLevel: String?,
    val contentRoots: List<ContentRoot>,
)

/**
 * A content root. [path] is rendered like every location of the bundled MCP Server (relative to the project
 * directory, `..` for roots next to it); [outsideProjectDir] tells that the root is not under the project directory.
 * [omittedFolders] counts folders left out to keep the output bounded.
 */
data class ContentRoot(
    val path: String,
    val outsideProjectDir: Boolean,
    val folders: List<SourceFolder>,
    val omittedFolders: Int = 0,
)

data class SourceFolder(val path: String, val kind: FolderKind, val generated: Boolean = false)

enum class FolderKind { SOURCE, TEST, RESOURCE, TEST_RESOURCE, EXCLUDED }

/** The scope of a dependency, as in the IDE's module settings. */
enum class DependencyScope { COMPILE, TEST, RUNTIME, PROVIDED }

/** What a module depends on. */
sealed interface DependencyTarget {
    val name: String

    /** [missing]: the module named in the dependency does not exist (e.g. it was removed or unloaded). */
    data class Module(override val name: String, val missing: Boolean = false) : DependencyTarget

    /** [coordinates]: `group:artifact:version` when the IDE knows them (Maven and Gradle libraries). */
    data class Library(override val name: String, val coordinates: String? = null) : DependencyTarget
}

/** One declared dependency (an order entry) of module [from]. */
data class Dependency(
    val from: String,
    val target: DependencyTarget,
    val scope: DependencyScope,
    val exported: Boolean,
)

/** The modules of a project and their declared dependencies, as the gateway reads them in one go. */
data class ProjectGraph(val modules: Set<String>, val dependencies: List<Dependency>)
