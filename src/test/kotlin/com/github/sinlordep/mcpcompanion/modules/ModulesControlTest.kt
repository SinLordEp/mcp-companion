package com.github.sinlordep.mcpcompanion.modules

import com.github.sinlordep.mcpcompanion.common.modulegraph.DependencyScope
import com.github.sinlordep.mcpcompanion.common.modulegraph.DependencyTarget
import com.github.sinlordep.mcpcompanion.common.modulegraph.GraphDirection
import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `list` and `graph` over a fake project model: traversal, depth, direction, scopes, paging, argument checks. */
class ModulesControlTest {

    private val model = FakeProjectModel()
    private val control = ModulesControl.create(model)

    private fun call(action: ModulesAction, arguments: ModulesArguments = ModulesArguments()) =
        runBlocking { control.execute(action, arguments) }

    private fun graph(arguments: ModulesArguments) =
        call(ModulesAction.GRAPH, arguments).value() as ModulesOutcome.Graph

    private fun ModulesOutcome.Graph.edges() = page.items.map {
        "${it.dependency.from}>${it.dependency.target.name}@${it.depth}"
    }

    /** app -> service -> core, app -> util (test), service -> util (runtime, exported), tools -> core. */
    private fun layered() = model.add("app", "core", "service", "tools", "util")
        .dependsOn("app", "service")
        .dependsOn("app", "util", DependencyScope.TEST)
        .dependsOn("service", "core")
        .dependsOn("service", "util", DependencyScope.RUNTIME, exported = true)
        .dependsOn("tools", "core")

    @Test
    fun `list returns every module sorted by name with its roots`() {
        model.module(
            "b",
            ContentRoot(
                "../sibling",
                outsideProjectDir = true,
                listOf(SourceFolder("../sibling/src", FolderKind.SOURCE)),
            ),
        )
            .module("a")
        val outcome = call(ModulesAction.LIST).value() as ModulesOutcome.Modules
        assertEquals(listOf("a", "b"), outcome.page.items.map { it.name })
        assertEquals(2, outcome.total)
        assertTrue(outcome.page.items[1].contentRoots.single().outsideProjectDir)
    }

    @Test
    fun `list pages and filters by an exact module name`() {
        model.add("m1", "m2", "m3")
        val first = call(ModulesAction.LIST, ModulesArguments(limit = 2)).value() as ModulesOutcome.Modules
        assertEquals(listOf("m1", "m2"), first.page.items.map { it.name })
        assertEquals(2, first.page.nextOffset)
        val second = call(ModulesAction.LIST, ModulesArguments(limit = 2, offset = 2)).value() as ModulesOutcome.Modules
        assertEquals(listOf("m3"), second.page.items.map { it.name })
        assertFalse(second.page.hasMore)

        val one = call(ModulesAction.LIST, ModulesArguments(module = "m2")).value() as ModulesOutcome.Modules
        assertEquals(listOf("m2"), one.page.items.map { it.name })
        assertEquals(ErrorCode.NOT_FOUND, call(ModulesAction.LIST, ModulesArguments(module = "M2")).code())
    }

    @Test
    fun `the whole graph lists module edges with scope and exported, libraries only when asked`() {
        layered().usesLibrary("core", "Gradle: org.slf4j:slf4j-api:2.0.16", "org.slf4j:slf4j-api:2.0.16")
        val whole = graph(ModulesArguments())
        assertNull(whole.root)
        assertEquals(5, whole.reached)
        assertEquals(
            listOf("app>service@null", "app>util@null", "service>core@null", "service>util@null", "tools>core@null"),
            whole.edges(),
        )
        val runtime = whole.page.items.single { it.dependency.from == "service" && it.dependency.target.name == "util" }
        assertEquals(DependencyScope.RUNTIME, runtime.dependency.scope)
        assertTrue(runtime.dependency.exported)

        val withLibraries = graph(ModulesArguments(includeLibraries = true))
        val library = withLibraries.page.items.single {
            it.dependency.target is DependencyTarget.Library
        }.dependency.target
        assertEquals("org.slf4j:slf4j-api:2.0.16", (library as DependencyTarget.Library).coordinates)
        assertEquals(6, withLibraries.total)
    }

    @Test
    fun `a walk follows dependencies breadth first with depths and stops at depth`() {
        layered()
        val all = graph(ModulesArguments(module = "app"))
        assertEquals(GraphDirection.DEPENDENCIES, all.direction)
        assertEquals(listOf("app>service@1", "app>util@1", "service>core@2", "service>util@2"), all.edges())
        assertEquals(4, all.reached)

        val direct = graph(ModulesArguments(module = "app", depth = 1))
        assertEquals(listOf("app>service@1", "app>util@1"), direct.edges())
        assertEquals(3, direct.reached)
    }

    @Test
    fun `a walk towards dependents finds who depends on a module`() {
        layered()
        val dependents = graph(ModulesArguments(module = "core", direction = GraphDirection.DEPENDENTS))
        assertEquals(listOf("service>core@1", "tools>core@1", "app>service@2"), dependents.edges())
        assertEquals(setOf("core", "service", "tools", "app").size, dependents.reached)
    }

    @Test
    fun `a walk through a cycle reports each edge once and terminates`() {
        model.add("a", "b", "c").dependsOn("a", "b").dependsOn("b", "c").dependsOn("c", "a")
        assertEquals(listOf("a>b@1", "b>c@2", "c>a@3"), graph(ModulesArguments(module = "a")).edges())
    }

    @Test
    fun `a dependency on a missing module is an edge without a walk beyond it`() {
        model.add("a").dependsOn("a", "gone")
        val walk = graph(ModulesArguments(module = "a"))
        val edge = walk.page.items.single().dependency.target as DependencyTarget.Module
        assertTrue(edge.missing)
        assertEquals(1, walk.reached)
    }

    @Test
    fun `graph pages are stable and continue with nextOffset`() {
        model.add("hub", *Array(30) { "m%02d".format(it) })
        repeat(30) { model.dependsOn("hub", "m%02d".format(29 - it)) }
        val pages = generateSequence(graph(ModulesArguments(module = "hub", limit = 7))) { previous ->
            previous.page.nextOffset?.let { graph(ModulesArguments(module = "hub", limit = 7, offset = it)) }
        }.toList()
        assertEquals(5, pages.size)
        val targets = pages.flatMap { page -> page.page.items.map { it.dependency.target.name } }
        assertEquals(List(30) { "m%02d".format(it) }, targets)
        assertTrue(pages.all { it.total == 30 })
    }

    @Test
    fun `a long chain is walked without recursion`() {
        val size = 50_000
        model.add(*Array(size) { "n$it" })
        for (i in 0 until size - 1) model.dependsOn("n$i", "n${i + 1}")
        val walk = graph(ModulesArguments(module = "n0", limit = 1, offset = size - 2))
        assertEquals(size, walk.reached)
        assertEquals(listOf("n${size - 2}>n${size - 1}@${size - 1}"), walk.edges())
    }

    @Test
    fun `graph rejects inconsistent arguments`() {
        layered()
        val noRoot = call(ModulesAction.GRAPH, ModulesArguments(depth = 2))
        assertEquals(ErrorCode.INVALID_ARGUMENT, noRoot.code())
        assertTrue(noRoot.message(), "module" in noRoot.message())
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            call(ModulesAction.GRAPH, ModulesArguments(module = "app", depth = 0)).code(),
        )
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            call(
                ModulesAction.GRAPH,
                ModulesArguments(module = "core", direction = GraphDirection.DEPENDENTS, includeLibraries = true),
            ).code(),
        )
        assertEquals(ErrorCode.NOT_FOUND, call(ModulesAction.GRAPH, ModulesArguments(module = "nope")).code())
        assertEquals(ErrorCode.INVALID_ARGUMENT, call(ModulesAction.GRAPH, ModulesArguments(limit = 0)).code())
        assertEquals(ErrorCode.INVALID_ARGUMENT, call(ModulesAction.GRAPH, ModulesArguments(offset = -1)).code())
    }

    @Test
    fun `parameters of another action are rejected before the model is read`() {
        val rejected = call(ModulesAction.LIST, ModulesArguments(depth = 1, ignoreTestScope = true))
        assertEquals(ErrorCode.INVALID_ARGUMENT, rejected.code())
        assertEquals("action=list does not take depth, ignoreTestScope.", rejected.message())
        assertEquals(ErrorCode.INVALID_ARGUMENT, call(ModulesAction.CYCLES, ModulesArguments(module = "a")).code())
        assertEquals(0, model.graphCalls)
    }
}
