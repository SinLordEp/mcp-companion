package com.github.sinlordep.mcpcompanion.modules

import com.github.sinlordep.mcpcompanion.common.modulegraph.Dependency
import com.github.sinlordep.mcpcompanion.common.modulegraph.DependencyScope
import com.github.sinlordep.mcpcompanion.common.modulegraph.DependencyTarget

/**
 * Renders dependency edges as a Mermaid flowchart (`flowchart LR`, an arrow from a module to what it depends on). Node
 * ids are generated (`m0`, `m1` for modules, `l0` for libraries) and names are only labels, quoted and escaped, so any
 * module name is safe. At most [MAX_EDGES] edges; [Diagram.omittedEdges] counts the rest. Edge labels give the scope
 * (`compile`, `test`, `runtime`, `provided`) and `exported`; test-scope edges are dotted; a missing module is labelled
 * `(missing)`, libraries are drawn rounded. [nodes] are drawn even without an edge (modules nothing depends on and
 * that depend on nothing), up to [MAX_NODES] nodes in all; [Diagram.omittedNodes] counts those left out.
 */
object MermaidGraph {

    const val MAX_EDGES: Int = 500
    const val MAX_NODES: Int = 1_000

    data class Diagram(val text: String, val edges: Int, val omittedEdges: Int, val omittedNodes: Int = 0)

    fun render(edges: List<Dependency>, nodes: Collection<String> = emptyList(), root: String? = null): Diagram {
        val shown = edges.take(MAX_EDGES)
        val connected = (
            shown.map { it.from } + shown.mapNotNull { (it.target as? DependencyTarget.Module)?.name } +
                listOfNotNull(root)
            ).toSet()
        val isolated = nodes.filterNot { it in connected }.distinct().sorted()
        val kept = isolated.take((MAX_NODES - connected.size).coerceAtLeast(0))
        val modules = (connected + kept).sorted()
        val libraries = shown.mapNotNull { it.target as? DependencyTarget.Library }.map { it.name }.distinct().sorted()
        val moduleIds = modules.withIndex().associate { (index, name) -> name to "m$index" }
        val libraryIds = libraries.withIndex().associate { (index, name) -> name to "l$index" }
        val missing = shown.mapNotNull { (it.target as? DependencyTarget.Module)?.takeIf { target -> target.missing } }
            .map { it.name }.toSet()
        val text = buildString {
            appendLine("flowchart LR")
            for (name in modules) {
                val label = if (name in missing) "$name (missing)" else name
                appendLine("  ${moduleIds.getValue(name)}[\"${escape(label)}\"]")
            }
            for (name in libraries) appendLine("  ${libraryIds.getValue(name)}([\"${escape(name)}\"])")
            for (edge in shown) {
                val target = edge.target
                val to = if (target is DependencyTarget.Library) {
                    libraryIds.getValue(target.name)
                } else {
                    moduleIds.getValue(target.name)
                }
                val arrow = if (edge.scope == DependencyScope.TEST) "-.->" else "-->"
                val label = edge.scope.name.lowercase() + if (edge.exported) ", exported" else ""
                appendLine("  ${moduleIds.getValue(edge.from)} $arrow|$label| $to")
            }
            root?.let { appendLine("  style ${moduleIds.getValue(it)} stroke-width:3px") }
        }
        return Diagram(text.trimEnd(), shown.size, edges.size - shown.size, isolated.size - kept.size)
    }

    /** Mermaid entity codes for the characters that end or break a quoted label. */
    private fun escape(label: String): String = label
        .replace("#", "#35;")
        .replace("\"", "#quot;")
        .replace("<", "#lt;")
        .replace(">", "#gt;")
        .replace("\n", " ")
}
