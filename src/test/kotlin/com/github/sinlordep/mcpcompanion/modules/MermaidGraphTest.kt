package com.github.sinlordep.mcpcompanion.modules

import com.github.sinlordep.mcpcompanion.common.modulegraph.Dependency
import com.github.sinlordep.mcpcompanion.common.modulegraph.DependencyScope
import com.github.sinlordep.mcpcompanion.common.modulegraph.DependencyTarget
import com.github.sinlordep.mcpcompanion.common.modulegraph.GraphDirection
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `graph format=mermaid`: generated node ids, escaped labels, edge styles, the bound. */
class MermaidGraphTest {

    private fun module(
        from: String,
        to: String,
        scope: DependencyScope = DependencyScope.COMPILE,
        exported: Boolean = false,
    ) = Dependency(from, DependencyTarget.Module(to), scope, exported)

    @Test
    fun `modules get generated ids, names are quoted labels, scopes are edge labels`() {
        val diagram = MermaidGraph.render(
            listOf(
                module("app.main", "lib-core", exported = true),
                module("app.main", "test kit \"x\"", DependencyScope.TEST),
                Dependency("lib-core", DependencyTarget.Library("Gradle: a:b:1 <x>"), DependencyScope.RUNTIME, false),
                Dependency("lib-core", DependencyTarget.Module("gone", missing = true), DependencyScope.COMPILE, false),
            ),
            nodes = listOf("lonely"),
            root = "app.main",
        )
        assertEquals(
            """
            flowchart LR
              m0["app.main"]
              m1["gone (missing)"]
              m2["lib-core"]
              m3["lonely"]
              m4["test kit #quot;x#quot;"]
              l0(["Gradle: a:b:1 #lt;x#gt;"])
              m0 -->|compile, exported| m2
              m0 -.->|test| m4
              m2 -->|runtime| l0
              m2 -->|compile| m1
              style m0 stroke-width:3px
            """.trimIndent(),
            diagram.text,
        )
        assertEquals(4, diagram.edges)
        assertEquals(0, diagram.omittedEdges)
    }

    @Test
    fun `at most MAX_EDGES edges, the rest is counted`() {
        val edges = (0..MermaidGraph.MAX_EDGES + 9).map { module("m$it", "m${it + 1}") }
        val diagram = MermaidGraph.render(edges)
        assertEquals(MermaidGraph.MAX_EDGES, diagram.edges)
        assertEquals(10, diagram.omittedEdges)
        assertEquals(MermaidGraph.MAX_EDGES, diagram.text.lines().count { "-->" in it })
    }

    @Test
    fun `modules without edges are drawn up to MAX_NODES nodes in all`() {
        val lonely = (1..MermaidGraph.MAX_NODES + 5).map { "lonely%04d".format(it) }
        val diagram = MermaidGraph.render(listOf(module("a", "b")), nodes = lonely)
        assertEquals(MermaidGraph.MAX_NODES, diagram.text.lines().count { it.trimStart().startsWith("m") && "[" in it })
        assertEquals(7, diagram.omittedNodes)
        assertTrue("a and b are kept", "[\"a\"]" in diagram.text && "[\"b\"]" in diagram.text)
    }

    @Test
    fun `graph with format=mermaid renders the walk, and refuses paging`() = runBlocking {
        val model = FakeProjectModel().add("app", "core", "util").dependsOn("app", "core").dependsOn("core", "util")
        val control = ModulesControl.create(model)
        val outcome = control.execute(
            ModulesAction.GRAPH,
            ModulesArguments(module = "core", direction = GraphDirection.DEPENDENTS, format = GraphFormat.MERMAID),
        ).value() as ModulesOutcome.Diagram
        assertEquals(2, outcome.reached)
        assertTrue(outcome.diagram.text, "m0 -->|compile| m1" in outcome.diagram.text)
        assertFalse(outcome.diagram.text, "util" in outcome.diagram.text)

        val paged = control.execute(ModulesAction.GRAPH, ModulesArguments(format = GraphFormat.MERMAID, limit = 5))
        assertEquals(ErrorCode.INVALID_ARGUMENT, paged.code())
        val cycles = control.execute(ModulesAction.CYCLES, ModulesArguments(format = GraphFormat.MERMAID))
        assertEquals(ErrorCode.INVALID_ARGUMENT, cycles.code())
    }
}
