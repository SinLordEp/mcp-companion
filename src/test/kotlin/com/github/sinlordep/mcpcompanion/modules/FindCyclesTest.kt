package com.github.sinlordep.mcpcompanion.modules

import com.github.sinlordep.mcpcompanion.common.modulegraph.DependencyScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `cycles`: strongly connected groups, self loops, test-scope filter, bounds; pure, no IDE. */
class FindCyclesTest {

    private val model = FakeProjectModel()

    private fun cycles(ignoreTestScope: Boolean = false) =
        FindCycles.cyclesOf(runBlocking { model.graph(includeLibraries = false) }, ignoreTestScope)

    private fun DependencyCycle.edgeNames() = edges.map { "${it.from}>${it.target.name}" }

    @Test
    fun `an acyclic graph has no cycles`() {
        model.add("a", "b", "c").dependsOn("a", "b").dependsOn("b", "c").dependsOn("a", "c")
        assertEquals(emptyList<DependencyCycle>(), cycles())
    }

    @Test
    fun `a self loop is a cycle of one module`() {
        model.add("a", "b").dependsOn("a", "a").dependsOn("a", "b")
        val cycle = cycles().single()
        assertEquals(listOf("a"), cycle.modules)
        assertEquals(listOf("a", "a"), cycle.cycle)
        assertEquals(listOf("a>a"), cycle.edgeNames())
    }

    @Test
    fun `two overlapping cycles form one group with all their edges and a shortest cycle`() {
        // a -> b -> a and b -> c -> d -> b share b; e hangs off the group without being part of it.
        model.add("a", "b", "c", "d", "e")
            .dependsOn("a", "b").dependsOn("b", "a")
            .dependsOn("b", "c").dependsOn("c", "d").dependsOn("d", "b")
            .dependsOn("d", "e")
        val group = cycles().single()
        assertEquals(listOf("a", "b", "c", "d"), group.modules)
        assertEquals(listOf("a", "b", "a"), group.cycle)
        assertEquals(listOf("a>b", "b>a", "b>c", "c>d", "d>b"), group.edgeNames())
    }

    @Test
    fun `separate groups are ordered by their first module`() {
        model.add("x", "y", "a", "b").dependsOn("y", "x").dependsOn("x", "y").dependsOn("b", "a").dependsOn("a", "b")
        assertEquals(listOf(listOf("a", "b"), listOf("x", "y")), cycles().map { it.modules })
    }

    @Test
    fun `the shortest cycle is found even when a longer one comes first`() {
        model.add("a", "b", "c", "d").dependsOn("a", "b").dependsOn("b", "c").dependsOn("c", "a")
            .dependsOn("a", "d").dependsOn("d", "a")
        assertEquals(listOf("a", "d", "a"), cycles().single().cycle)
    }

    @Test
    fun `test-scope edges can be ignored`() {
        model.add("main", "testkit")
            .dependsOn("testkit", "main")
            .dependsOn("main", "testkit", DependencyScope.TEST)
        assertEquals(1, cycles().size)
        assertEquals(DependencyScope.TEST, cycles().single().edges.single { it.from == "main" }.scope)
        assertEquals(emptyList<DependencyCycle>(), cycles(ignoreTestScope = true))
    }

    @Test
    fun `dependencies on missing modules are not part of cycles`() {
        model.add("a").dependsOn("a", "gone")
        assertEquals(emptyList<DependencyCycle>(), cycles())
    }

    @Test
    fun `a long chain closed into one cycle needs no recursion`() {
        val size = 100_000
        model.add(*Array(size) { "n%06d".format(it) })
        for (i in 0 until size) model.dependsOn("n%06d".format(i), "n%06d".format((i + 1) % size))
        val group = cycles().single()
        assertEquals(size, group.modules.size)
        assertEquals(size + 1, group.cycle.size)
        assertEquals(FindCycles.MAX_EDGES_PER_CYCLE, group.edges.size)
        assertEquals(size - FindCycles.MAX_EDGES_PER_CYCLE, group.omittedEdges)
    }

    @Test
    fun `cycles are paged through the action`() {
        model.add("a", "b", "c", "d").dependsOn("a", "b").dependsOn("b", "a").dependsOn("c", "d").dependsOn("d", "c")
        val control = ModulesControl.create(model)
        val page = runBlocking { control.execute(ModulesAction.CYCLES, ModulesArguments(limit = 1)) }.value()
            as ModulesOutcome.Cycles
        assertEquals(2, page.total)
        assertEquals(listOf("a", "b"), page.page.items.single().modules)
        assertTrue(page.page.hasMore)
    }
}
