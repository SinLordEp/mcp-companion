package com.github.sinlordep.mcpcompanion.common.build

/** What a build through the IDE's own build path (`ProjectTaskManager`) covers. */
sealed interface BuildScope {
    /** Every module, incrementally: what the IDE's Build Project and "Reload Changed Classes" build. */
    data object AllModules : BuildScope

    /** These modules (exact names) and the modules they depend on, tests included: the IDE's Build Module. */
    data class Modules(val names: List<String>) : BuildScope
}

enum class ProblemSeverity { ERROR, WARNING }

/**
 * One compiler message. [path] is in the tool output form (relative to the project directory where possible);
 * [line] and [column] are 1-based. Location fields are null when the build system gives none.
 */
data class BuildProblem(
    val severity: ProblemSeverity,
    val message: String,
    val path: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

/** Where a build's messages came from, which decides whether they carry positions. */
enum class MessageSource {
    /** The IDE's own compiler (JPS) reported them, with file, line and column. */
    COMPILER,

    /**
     * Read from the console output of the build tool the build was delegated to (Gradle, Maven): lines in the formats
     * of javac, kotlinc and the Maven compiler plugin, with the positions those formats carry.
     */
    BUILD_OUTPUT,

    /** The build reported no message this plugin can read. */
    NONE,
}

/** How a build ended. */
enum class BuildResult {
    SUCCEEDED,
    FAILED,
    ABORTED,
    ;

    companion object {
        /**
         * The result from the IDE's flags and the messages read. A delegated Maven build that fails ends as
         * "aborted" in `ProjectTaskManager.Result` (seen end to end with a compile error and with an unresolvable
         * dependency), so an aborted build with errors counts as failed; aborted stays for a build that ended without
         * any error (cancelled, or stopped before it compiled).
         */
        fun of(aborted: Boolean, hasErrors: Boolean, problems: List<BuildProblem>): BuildResult {
            val errors = problems.any { it.severity == ProblemSeverity.ERROR }
            return when {
                hasErrors || (aborted && errors) -> FAILED
                aborted -> ABORTED
                else -> SUCCEEDED
            }
        }
    }
}

/** A finished build: its result, its errors and warnings (bounded, see [omittedProblems]) and their source. */
data class BuildRun(
    val result: BuildResult,
    val problems: List<BuildProblem>,
    val source: MessageSource,
    val omittedProblems: Int = 0,
)
