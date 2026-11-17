package com.github.sinlordep.mcpcompanion.common.modulegraph

/** Which way a walk of the module graph goes from its root module. */
enum class GraphDirection {
    /** The modules the root depends on. */
    DEPENDENCIES,

    /** The modules that depend on the root. */
    DEPENDENTS,
}

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

/** A dependency in a graph walk; [depth] is the number of edges between the walk's root and the edge's far end. */
data class GraphEdge(val dependency: Dependency, val depth: Int? = null)
