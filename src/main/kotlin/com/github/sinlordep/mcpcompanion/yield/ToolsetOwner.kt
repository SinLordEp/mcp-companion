package com.github.sinlordep.mcpcompanion.yield

/** Who ships an MCP toolset class, and whether that makes its tools official. */
sealed interface ToolsetOwner {

    /** Official tools are those that JetBrains ships; only they can make a toolset of ours step aside. */
    val isOfficial: Boolean

    /** The IDE itself: the class belongs to no plugin. */
    data object Platform : ToolsetOwner {
        override val isOfficial: Boolean = true
    }

    /**
     * A plugin. It is official when it is [bundled] with the IDE or [developedByJetBrains]. The second covers
     * JetBrains plugins installed from Marketplace and updated copies of bundled plugins, which no longer count as
     * bundled.
     */
    data class Plugin(val bundled: Boolean, val developedByJetBrains: Boolean) : ToolsetOwner {
        override val isOfficial: Boolean = bundled || developedByJetBrains
    }
}

/** Tells who ships a toolset class. Gateway boundary of `yield/`. */
fun interface ToolsetOwnerGateway {
    fun ownerOf(type: Class<*>): ToolsetOwner
}
