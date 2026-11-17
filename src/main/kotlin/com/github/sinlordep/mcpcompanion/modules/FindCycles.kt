package com.github.sinlordep.mcpcompanion.modules

import com.github.sinlordep.mcpcompanion.common.paging.page
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.map

/**
 * `cycles`: groups of modules that depend on each other (strongly connected components with more than one module,
 * and modules that depend on themselves), each with one shortest cycle and the edges among its modules.
 * `ignoreTestScope` leaves test-scope dependencies out, since a test-only cycle does not break compilation of the
 * production code. Dependencies on missing modules cannot be part of a cycle.
 */
class FindCycles(private val model: ProjectModelGateway) : ModulesActionHandler {

    override val action = ModulesAction.CYCLES
    override val parameters = setOf(ModulesParameter.IGNORE_TEST_SCOPE, ModulesParameter.LIMIT, ModulesParameter.OFFSET)

    override suspend fun handle(request: ModulesArguments): ToolResult<ModulesOutcome> =
        request.pageRequest().map { pageRequest ->
            val ignoreTest = request.ignoreTestScope == true
            val cycles = cyclesOf(model.graph(includeLibraries = false), ignoreTest)
            ModulesOutcome.Cycles(cycles.asSequence().page(pageRequest), cycles.size, ignoreTest)
        }

    companion object {
        /** Bound of [DependencyCycle.edges] per group. */
        const val MAX_EDGES_PER_CYCLE: Int = 500

        /** Every cycle group of [graph], ordered by its first module. Pure; linear in modules plus edges. */
        fun cyclesOf(graph: ProjectGraph, ignoreTestScope: Boolean): List<DependencyCycle> {
            val edges = graph.dependencies
                .filter { it.target is DependencyTarget.Module && it.target.name in graph.modules }
                .filterNot { ignoreTestScope && it.scope == DependencyScope.TEST }
                .sortedWith(DependencyGraph.EDGE_ORDER)
            val successors = edges.groupBy({
                it.from
            }, { it.target.name }).mapValues { (_, targets) -> targets.distinct() }
            val components = StronglyConnectedComponents.of(graph.modules.sorted()) { successors[it].orEmpty() }
            return components
                .filter { it.size > 1 || it.single() in successors[it.single()].orEmpty() }
                .map { describe(it.toSet(), edges, successors) }
                .sortedBy { it.modules.first() }
        }

        private fun describe(
            members: Set<String>,
            edges: List<Dependency>,
            successors: Map<String, List<String>>,
        ): DependencyCycle {
            val inside = edges.filter { it.from in members && it.target.name in members }
            val modules = members.sorted()
            return DependencyCycle(
                modules = modules,
                cycle = shortestCycle(modules.first(), members, successors),
                edges = inside.take(MAX_EDGES_PER_CYCLE),
                omittedEdges = (inside.size - MAX_EDGES_PER_CYCLE).coerceAtLeast(0),
            )
        }

        /** A shortest closed path from [start] back to itself within [members] (breadth first, iterative). */
        private fun shortestCycle(
            start: String,
            members: Set<String>,
            successors: Map<String, List<String>>,
        ): List<String> {
            val parent = HashMap<String, String>()
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) {
                val module = queue.removeFirst()
                for (next in successors[module].orEmpty()) {
                    if (next == start) return pathTo(module, start, parent) + start
                    if (next in members && next !in parent) {
                        parent[next] = module
                        queue.addLast(next)
                    }
                }
            }
            error("$start is in a cycle group but no cycle leads back to it")
        }

        private fun pathTo(end: String, start: String, parent: Map<String, String>): List<String> {
            val path = ArrayList<String>()
            var at = end
            while (at != start) {
                path += at
                at = parent.getValue(at)
            }
            path += start
            return path.reversed()
        }
    }
}
