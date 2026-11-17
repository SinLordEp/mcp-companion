package com.github.sinlordep.mcpcompanion.common.build

/**
 * Which events that happen while a build runs belong to it. Messages are only read from what belongs to the build:
 *
 * - a compilation of the IDE's compiler (JPS) belongs when its compile scope carries the build's session id (the IDE
 *   puts the `ProjectTaskContext`'s session id on the scope it compiles);
 * - an execution (the console of a build delegated to Gradle or Maven) belongs when it is in the build's project, is
 *   a build tool's build task (for external systems: a task configuration whose progress goes to the Build tool
 *   window, which the IDE's build runner sets; an application that Gradle runs for the IDE has none) and is not a
 *   configuration of the project's run configuration list (a configuration the user or `mc_run` starts, also a Gradle
 *   task run from the Gradle tool window, which the IDE adds as a temporary configuration).
 *
 * What still mixes in (no public link from a build to the execution its runner starts): another delegated build of
 * the same project that runs at the same time, e.g. one the user starts from the IDE's Build menu.
 */
object BuildAttribution {

    /** What the gateway knows about an execution that started while the build ran. */
    data class ExecutionFacts(val sameProject: Boolean, val buildToolTask: Boolean, val listedConfiguration: Boolean)

    fun executionBelongs(facts: ExecutionFacts): Boolean =
        facts.sameProject && facts.buildToolTask && !facts.listedConfiguration

    /** [compilationSession] is the session id on the compiled scope; the build's is [buildSession]. */
    fun compilationBelongs(compilationSession: Any?, buildSession: Any): Boolean = compilationSession === buildSession
}
