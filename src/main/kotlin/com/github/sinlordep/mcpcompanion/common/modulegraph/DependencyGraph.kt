package com.github.sinlordep.mcpcompanion.common.modulegraph

/**
 * The declared dependencies of a project, indexed both ways, in a stable order. Library dependencies are leaves; a
 * dependency on a missing module is an edge to a module that has no node.
 */
class DependencyGraph(graph: ProjectGraph) {

    val modules: List<String> = graph.modules.sorted()

    /** Every dependency, in [EDGE_ORDER]. */
    val dependencies: List<Dependency> = graph.dependencies.sortedWith(EDGE_ORDER)

    private val outgoing: Map<String, List<Dependency>> = dependencies.groupBy { it.from }

    private val incoming: Map<String, List<Dependency>> =
        dependencies.filter { it.target is DependencyTarget.Module }.groupBy { it.target.name }

    private val moduleSet: Set<String> = graph.modules

    operator fun contains(module: String): Boolean = module in moduleSet

    /** What [module] depends on, modules before libraries. */
    fun dependenciesOf(module: String): List<Dependency> = outgoing[module].orEmpty()

    /** The module dependencies on [module]. */
    fun dependentsOf(module: String): List<Dependency> = incoming[module].orEmpty()

    /**
     * Breadth-first walk from [root] in [direction], at most [maxDepth] edges deep. Each dependency is reported once,
     * also one that leads back to a module already reached (so cycles stay visible). Iterative: no recursion depth
     * limit on long chains.
     */
    fun walk(root: String, direction: GraphDirection, maxDepth: Int): Walk {
        val levels = linkedMapOf(root to 0)
        val queue = ArrayDeque(listOf(root))
        val edges = ArrayList<GraphEdge>()
        while (queue.isNotEmpty()) {
            val module = queue.removeFirst()
            val level = levels.getValue(module)
            if (level >= maxDepth) continue
            val next = when (direction) {
                GraphDirection.DEPENDENCIES -> dependenciesOf(module).map { it to it.target.moduleName() }
                GraphDirection.DEPENDENTS -> dependentsOf(module).map { it to it.from }
            }
            for ((dependency, far) in next) {
                edges += GraphEdge(dependency, level + 1)
                if (far != null && far in moduleSet && far !in levels) {
                    levels[far] = level + 1
                    queue.addLast(far)
                }
            }
        }
        return Walk(levels.keys, edges)
    }

    /** The modules a walk reached (root first) and the dependencies it went along. */
    data class Walk(val reached: Set<String>, val edges: List<GraphEdge>)

    companion object {
        /** From, then module dependencies before libraries, then target name, then scope. */
        val EDGE_ORDER: Comparator<Dependency> = compareBy<Dependency>(
            { it.from },
            { it.target is DependencyTarget.Library },
            { it.target.name },
            { it.scope },
        )

        private fun DependencyTarget.moduleName(): String? = (this as? DependencyTarget.Module)?.name
    }
}
