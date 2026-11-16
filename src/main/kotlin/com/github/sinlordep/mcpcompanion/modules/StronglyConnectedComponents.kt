package com.github.sinlordep.mcpcompanion.modules

/**
 * Tarjan's strongly connected components, iterative: the explicit work stack replaces recursion, so a chain of any
 * length cannot overflow the thread's stack. Linear in nodes plus edges. Successors outside [nodes] are ignored.
 */
internal object StronglyConnectedComponents {

    /** The components of the graph over [nodes]; each in the order Tarjan closes it, every node in exactly one. */
    fun of(nodes: Collection<String>, successors: (String) -> List<String>): List<List<String>> =
        Search(nodes.toSet(), successors).run(nodes)

    private class Search(private val nodes: Set<String>, private val successors: (String) -> List<String>) {
        private var nextIndex = 0
        private val index = HashMap<String, Int>()
        private val lowLink = HashMap<String, Int>()
        private val onStack = HashSet<String>()
        private val stack = ArrayDeque<String>()
        private val work = ArrayDeque<Pair<String, Iterator<String>>>()
        private val components = ArrayList<List<String>>()

        fun run(order: Collection<String>): List<List<String>> {
            for (node in order) {
                if (node !in index) explore(node)
            }
            return components
        }

        private fun explore(start: String) {
            enter(start)
            while (work.isNotEmpty()) {
                val (node, next) = work.last()
                if (next.hasNext()) {
                    val successor = next.next()
                    when {
                        successor !in nodes -> Unit
                        successor !in index -> enter(successor)
                        successor in onStack -> lower(node, index.getValue(successor))
                    }
                } else {
                    work.removeLast()
                    work.lastOrNull()?.let { (parent, _) -> lower(parent, lowLink.getValue(node)) }
                    if (lowLink[node] == index[node]) close(node)
                }
            }
        }

        private fun enter(node: String) {
            index[node] = nextIndex
            lowLink[node] = nextIndex
            nextIndex++
            stack.addLast(node)
            onStack += node
            work.addLast(node to successors(node).iterator())
        }

        private fun lower(node: String, value: Int) {
            if (value < lowLink.getValue(node)) lowLink[node] = value
        }

        private fun close(root: String) {
            val component = ArrayList<String>()
            do {
                val member = stack.removeLast()
                onStack -= member
                component += member
            } while (member != root)
            components += component
        }
    }
}
