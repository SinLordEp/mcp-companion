package com.github.sinlordep.mcpcompanion.run

/** Outcome of one hot swap into one debug session. */
sealed interface HotSwapReport {
    /** Name of the debug session, as the IDE shows it. */
    val session: String

    /** The changed classes were redefined in the running JVM. */
    data class Reloaded(override val session: String, val classes: List<String>, val messages: List<String>) :
        HotSwapReport

    /** The compiled classes are the ones the JVM already runs. */
    data class NothingToReload(override val session: String, val messages: List<String>) : HotSwapReport

    /** The build failed; nothing was reloaded. */
    data class CompilationFailed(override val session: String, val errors: List<CompilerError>) : HotSwapReport

    /** The JVM refused the new classes (e.g. a method was added or a signature changed); nothing was reloaded. */
    data class Rejected(override val session: String, val classes: List<String>, val reasons: List<String>) :
        HotSwapReport

    /** The process cannot reload classes at all, e.g. it is not a JVM debug session. */
    data class NotPossible(override val session: String, val reason: String) : HotSwapReport

    /** Whether restarting is the way out (the JVM refused, or the process cannot reload classes). */
    val restartHelps: Boolean get() = this is Rejected || this is NotPossible
}

/** One compiler error. Location fields are null when the compiler gives none. */
data class CompilerError(val message: String, val path: String?, val line: Int?, val column: Int?)
