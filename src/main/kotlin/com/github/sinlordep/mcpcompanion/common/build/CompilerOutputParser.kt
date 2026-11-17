package com.github.sinlordep.mcpcompanion.common.build

/**
 * Reads compiler messages out of a build tool's console output, line by line (strategy per format):
 *
 * - javac, as Gradle prints it: `/abs/App.java:12: error: cannot find symbol`, followed by the source line and a
 *   caret line whose `^` gives the column;
 * - the Maven compiler plugin: `[ERROR] /abs/App.java:[12,17] cannot find symbol`;
 * - kotlinc 2.x: `e: file:///abs/App.kt:12:17 Unresolved reference 'x'.` (`w:` for warnings).
 *
 * Besides compiler lines it keeps the build tool's own reason for a failure, which has no position: the line after
 * Gradle's `* What went wrong:` and Maven's `[ERROR] Failed to execute goal ...` ([failures]), e.g. a dependency that
 * could not be resolved.
 *
 * Pure and stateful per build: feed every line of one process to [accept], then read [problems]. Paths are returned
 * as printed (absolute, `/` separators); the caller renders them. ANSI escape sequences are removed first.
 */
class CompilerOutputParser {

    private val found = LinkedHashSet<RawProblem>()

    /** A javac message waiting for its caret line: the source line comes first, then `^`. */
    private var pending: RawProblem? = null
    private var linesSincePending = 0

    private val failureReasons = LinkedHashSet<String>()
    private var afterWhatWentWrong = false

    /** One problem as printed; [path] is the printed path. */
    data class RawProblem(
        val severity: ProblemSeverity,
        val message: String,
        val path: String,
        val line: Int,
        val column: Int?,
    )

    fun accept(rawLine: String) {
        val line = ANSI.replace(rawLine, "").trimEnd('\r', '\n')
        noteFailure(line)
        val caret = caretColumn(line)
        val problem = FORMATS.firstNotNullOfOrNull { parse(it, line) }
        when {
            pending != null && caret != null -> completePending(caret)

            problem != null -> {
                flushPending()
                if (problem.column == null) startPending(problem) else found += problem
            }

            pending != null && ++linesSincePending > CARET_SEARCH_LINES -> flushPending()
        }
    }

    private fun startPending(problem: RawProblem) {
        pending = problem
        linesSincePending = 0
    }

    /** Every problem seen so far, de-duplicated, in output order. */
    fun problems(): List<RawProblem> {
        flushPending()
        return found.toList()
    }

    /** The build tool's reasons for a failed build, without position, in output order. */
    fun failures(): List<String> = failureReasons.toList()

    private fun noteFailure(line: String) {
        val text = line.trim()
        when {
            afterWhatWentWrong && text.isNotEmpty() -> {
                failureReasons += text.take(MAX_REASON)
                afterWhatWentWrong = false
            }

            text == GRADLE_FAILURE -> afterWhatWentWrong = true

            else -> MAVEN_FAILURE.matchEntire(text)?.let { failureReasons += it.groupValues[1].take(MAX_REASON) }
        }
    }

    private fun completePending(column: Int) {
        pending?.let { found += it.copy(column = column) }
        pending = null
    }

    private fun flushPending() {
        pending?.let { found += it }
        pending = null
    }

    private fun caretColumn(line: String): Int? {
        val index = line.indexOf('^')
        return if (index >= 0 && line.isNotBlank() && line.trim() == "^") index + 1 else null
    }

    private fun parse(format: Regex, line: String): RawProblem? = format.matchEntire(line)?.let { match ->
        val groups = match.groups
        RawProblem(
            severity = severityOf(groups.text("kind")),
            message = groups.text("message").trim(),
            path = groups.text("path"),
            line = groups.text("line").toInt(),
            column = if (format === JAVAC) null else groups.text("column").toInt(),
        )
    }

    private fun MatchGroupCollection.text(name: String): String = checkNotNull(get(name)).value

    private fun severityOf(kind: String): ProblemSeverity =
        if (kind.lowercase().startsWith("e")) ProblemSeverity.ERROR else ProblemSeverity.WARNING

    private companion object {
        /** javac puts the caret line two lines after the message (the source line is between). */
        const val CARET_SEARCH_LINES = 2

        val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

        private const val ABSOLUTE = "(?:[A-Za-z]:)?[/\\\\]"

        /** javac; the column comes from the caret line that follows. */
        val JAVAC = Regex("^(?<path>$ABSOLUTE[^:]*?\\.java):(?<line>\\d+): (?<kind>error|warning): (?<message>.*)$")

        val MAVEN = Regex(
            "^\\[(?<kind>ERROR|WARNING)]\\s+(?<path>$ABSOLUTE.*?\\.(?:java|kt|kts|groovy|scala))" +
                ":\\[(?<line>\\d+),(?<column>\\d+)]\\s+(?<message>.*)$",
        )

        val KOTLIN = Regex(
            "^(?<kind>e|w): (?:file://)?(?<path>$ABSOLUTE.*?\\.kts?):(?<line>\\d+):(?<column>\\d+):? (?<message>.*)$",
        )

        val FORMATS = listOf(MAVEN, KOTLIN, JAVAC)

        const val GRADLE_FAILURE = "* What went wrong:"
        val MAVEN_FAILURE = Regex("^\\[ERROR] (Failed to execute goal .*)$")
        const val MAX_REASON = 2_000
    }
}
