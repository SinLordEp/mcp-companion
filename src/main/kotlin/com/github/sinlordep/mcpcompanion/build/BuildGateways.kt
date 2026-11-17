package com.github.sinlordep.mcpcompanion.build

import com.github.sinlordep.mcpcompanion.common.build.BuildRun
import com.github.sinlordep.mcpcompanion.common.modulegraph.ProjectGraph

/** Gateway boundary: the IDE project around a module build. */
interface BuildEnvironment {
    /** False in safe mode: the IDE would not run the project's build scripts. */
    fun isTrusted(): Boolean

    /** The modules and their module dependencies, one consistent snapshot. */
    suspend fun moduleGraph(): ProjectGraph

    /** The builder of this IDE; null when none can build modules (no Java plugin). */
    fun builder(): ModuleBuilder?
}

/** Gateway boundary: builds modules through the IDE's own build path, so Gradle and Maven delegation applies. */
fun interface ModuleBuilder {
    /**
     * Builds [modules] (exact names) and the modules they depend on, and suspends until the IDE reports the end.
     * Never asks the user anything.
     */
    suspend fun build(modules: List<String>): BuildRun
}
