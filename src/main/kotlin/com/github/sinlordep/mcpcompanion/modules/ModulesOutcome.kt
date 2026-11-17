package com.github.sinlordep.mcpcompanion.modules

import com.github.sinlordep.mcpcompanion.common.paging.Page

/** What an `mc_modules` action produced. [total] counts the items of the whole result, across pages. */
sealed interface ModulesOutcome {

    data class Modules(val page: Page<ModuleDescription>, val total: Int) : ModulesOutcome

    /**
     * Dependency edges. With a [root], [reached] counts the modules the walk reached (the root included) and every
     * edge has its depth; without one, the whole graph.
     */
    data class Graph(
        val root: String?,
        val direction: GraphDirection?,
        val reached: Int,
        val page: Page<GraphEdge>,
        val total: Int,
    ) : ModulesOutcome

    data class Cycles(val page: Page<DependencyCycle>, val total: Int, val ignoredTestScope: Boolean) : ModulesOutcome
}

/** A dependency in a graph walk; [depth] is the number of edges between the walk's root and the edge's far end. */
data class GraphEdge(val dependency: Dependency, val depth: Int? = null)

/**
 * A strongly connected group of modules: every module of [modules] (sorted) reaches every other one. [cycle] is one
 * shortest cycle through the first module, written as a closed path (`[a, b, a]`; a self loop is `[a, a]`). [edges]
 * are the dependencies among the group's modules, which form all its cycles; [omittedEdges] were left out to keep the
 * output bounded.
 */
data class DependencyCycle(
    val modules: List<String>,
    val cycle: List<String>,
    val edges: List<Dependency>,
    val omittedEdges: Int = 0,
)
