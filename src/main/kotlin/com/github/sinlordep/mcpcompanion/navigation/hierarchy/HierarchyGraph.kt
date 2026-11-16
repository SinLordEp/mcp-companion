package com.github.sinlordep.mcpcompanion.navigation.hierarchy

/**
 * Mutable graph under construction by [BuildTypeHierarchy]: assigns node ids, deduplicates types by [TypeRef.key],
 * enforces the node limit and remembers whether anything was left out.
 */
internal class HierarchyGraph(root: TypeRef, private val maxNodes: Int) {

    private val refs = arrayListOf(root)
    private val ids = hashMapOf(root.key to ROOT)
    private val supertypes = HashMap<Int, List<Int>>()
    private val subtypes = HashMap<Int, List<Int>>()

    var truncated: Boolean = false
        private set

    fun ref(id: Int): TypeRef = refs[id]

    /** How many more types fit under the node limit. */
    val room: Int get() = maxNodes - refs.size

    fun contains(key: String): Boolean = key in ids

    fun markTruncated() {
        truncated = true
    }

    /**
     * Records [related] as the supertypes ([up]) or subtypes of node [from]. Types already in the graph are linked
     * again; new ones are added while the node limit allows. Returns the ids of the added nodes.
     */
    fun link(from: Int, related: List<TypeRef>, up: Boolean): List<Int> {
        val added = ArrayList<Int>()
        val linked = ArrayList<Int>()
        for (ref in related) {
            val id = ids[ref.key] ?: addOrNull(ref)?.also(added::add)
            if (id == null) {
                markTruncated()
            } else if (id !in linked) {
                linked += id
            }
        }
        (if (up) supertypes else subtypes)[from] = linked
        return added
    }

    fun build(): TypeHierarchy = TypeHierarchy(
        nodes = refs.mapIndexed { id, ref ->
            TypeNode(id, ref.symbol, supertypes[id], subtypes[id])
        },
        truncated = truncated,
    )

    private fun addOrNull(ref: TypeRef): Int? {
        if (refs.size >= maxNodes) return null
        refs += ref
        return (refs.size - 1).also { ids[ref.key] = it }
    }

    companion object {
        const val ROOT: Int = 0
    }
}
