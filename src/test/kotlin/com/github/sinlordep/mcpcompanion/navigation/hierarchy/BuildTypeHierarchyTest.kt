package com.github.sinlordep.mcpcompanion.navigation.hierarchy

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.symbols.SymbolHandle
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols.code
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols.value
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildTypeHierarchyTest {

    /**
     * ```
     *        Top
     *       /   \
     *    Left   Right        (interfaces)
     *       \   /
     *        Root
     *       /    \
     *    SubA    SubB
     *              \
     *              SubBB
     * ```
     * SubB also implements Left directly, so Left -> SubB would exist if walking down from Left.
     */
    private class FakeRelations(private val brokenAt: String? = null) : TypeRelationsGateway {
        val supers = mapOf("Root" to listOf("Left", "Right"), "Left" to listOf("Top"), "Right" to listOf("Top"))

        // Listed out of order, as from a parallel search; Wide has many subtypes.
        val subs = mapOf("Root" to listOf("SubB", "SubA"), "SubB" to listOf("SubBB"), "Wide" to List(500) { "W$it" })
        val asked = mutableListOf<String>()
        var described = 0

        private fun ref(name: String) = TypeRef(name, FakeSymbols.symbol(name))

        override suspend fun typeOf(target: SymbolHandle): ToolResult<TypeRef> =
            ToolResult.success(ref((target as FakeSymbols.NamedHandle).name))

        override suspend fun directSupertypes(type: TypeRef, choose: (List<String>) -> List<String>) =
            related(supers, type, Int.MAX_VALUE, choose)

        override suspend fun directSubtypes(type: TypeRef, limit: Int, choose: (List<String>) -> List<String>) =
            related(subs, type, limit, choose)

        private fun related(
            map: Map<String, List<String>>,
            type: TypeRef,
            limit: Int,
            choose: (List<String>) -> List<String>,
        ): ToolResult<List<TypeRef>> {
            asked += type.key
            if (type.key == brokenAt) return ToolResult.failure(ErrorCode.NOT_FOUND, "gone")
            return ToolResult.success(
                choose(map[type.key].orEmpty().take(limit)).map(::ref).also {
                    described += it.size
                },
            )
        }
    }

    private fun build(
        direction: HierarchyDirection = HierarchyDirection.BOTH,
        depth: Int? = null,
        maxNodes: Int? = null,
        relations: FakeRelations = FakeRelations(),
    ): ToolResult<TypeHierarchy> = runBlocking {
        val request = TypeHierarchyRequest.of(FakeSymbols.ANY_POSITION, direction, depth, maxNodes).value()
        BuildTypeHierarchy(FakeSymbols.locating(FakeSymbols.symbol("Root")), relations).execute(request)
    }

    private fun TypeHierarchy.node(name: String) = nodes.single { it.symbol.name == name }

    private fun TypeHierarchy.names(ids: List<Int>?) = ids?.map { nodes[it].symbol.name }

    @Test
    fun `walks both ways and merges a diamond into one node`() {
        val graph = build().value()
        assertEquals("Root", graph.nodes[0].symbol.name)
        assertEquals(listOf("Left", "Right"), graph.names(graph.nodes[0].supertypes))
        assertEquals(listOf("Top"), graph.names(graph.node("Left").supertypes))
        assertEquals(listOf("Top"), graph.names(graph.node("Right").supertypes))
        assertEquals(1, graph.nodes.count { it.symbol.name == "Top" })
        assertEquals(emptyList<Int>(), graph.node("Top").supertypes)
        assertEquals(listOf("SubA", "SubB"), graph.names(graph.nodes[0].subtypes))
        assertEquals(listOf("SubBB"), graph.names(graph.node("SubB").subtypes))
        assertNull("supertypes of subtypes are not explored", graph.node("SubB").supertypes)
        assertFalse(graph.truncated)
        assertEquals(7, graph.nodes.size)
    }

    @Test
    fun `one direction leaves the other unexplored`() {
        val up = build(HierarchyDirection.UP).value()
        assertNull(up.nodes[0].subtypes)
        assertEquals(4, up.nodes.size)
        val down = build(HierarchyDirection.DOWN).value()
        assertNull(down.nodes[0].supertypes)
        assertEquals(4, down.nodes.size)
    }

    @Test
    fun `depth limit truncates only when it cut off existing types`() {
        val cut = build(HierarchyDirection.UP, depth = 1).value()
        assertTrue(cut.truncated)
        assertNull("Left is at the depth limit", cut.node("Left").supertypes)

        assertFalse("Top has no supertypes, nothing was cut", build(HierarchyDirection.UP, depth = 2).value().truncated)
        assertTrue(build(HierarchyDirection.DOWN, depth = 1).value().truncated)
        assertFalse(build(HierarchyDirection.DOWN, depth = 2).value().truncated)
    }

    @Test
    fun `node limit caps the graph and truncates`() {
        val graph = build(maxNodes = 3).value()
        assertEquals(3, graph.nodes.size)
        assertTrue(graph.truncated)
        assertEquals(listOf("Left", "Right"), graph.names(graph.nodes[0].supertypes))
        assertEquals("no room for subtypes", emptyList<Int>(), graph.nodes[0].subtypes)
    }

    @Test
    fun `subtypes are sorted, and only those the node limit keeps are described`() {
        assertEquals(
            listOf("SubA", "SubB"),
            build(HierarchyDirection.DOWN).value().let {
                it.names(it.nodes[0].subtypes)
            },
        )

        val relations = FakeRelations()
        val graph = runBlocking {
            val request = TypeHierarchyRequest.of(FakeSymbols.ANY_POSITION, HierarchyDirection.DOWN, 1, 4).value()
            BuildTypeHierarchy(FakeSymbols.locating(FakeSymbols.symbol("Wide")), relations).execute(request)
        }.value()
        assertEquals(listOf("W0", "W1", "W10"), graph.names(graph.nodes[0].subtypes))
        assertTrue(graph.truncated)
        assertEquals("3 of 500 subtypes described", 3, relations.described)
    }

    @Test
    fun `a failure while walking ends the call`() {
        assertEquals(ErrorCode.NOT_FOUND, build(relations = FakeRelations(brokenAt = "Left")).code())
    }

    @Test
    fun `request limits are validated`() {
        for ((depth, nodes) in listOf(0 to 10, 21 to 10, 3 to 0, 3 to 1001)) {
            val result = TypeHierarchyRequest.of(FakeSymbols.ANY_POSITION, HierarchyDirection.BOTH, depth, nodes)
            assertEquals("$depth/$nodes", ErrorCode.INVALID_ARGUMENT, result.code())
        }
        val defaults = TypeHierarchyRequest.of(FakeSymbols.ANY_POSITION, HierarchyDirection.BOTH, null, null).value()
        assertEquals(TypeHierarchyRequest.DEFAULT_DEPTH, defaults.depth)
        assertEquals(TypeHierarchyRequest.DEFAULT_MAX_NODES, defaults.maxNodes)
    }
}
