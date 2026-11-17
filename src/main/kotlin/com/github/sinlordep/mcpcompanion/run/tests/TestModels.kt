package com.github.sinlordep.mcpcompanion.run.tests

/** What happened to one test, as the IDE's test tree shows it. */
enum class TestOutcome {
    PASSED,

    /**
     * An assertion failed, or the test threw. The IDE's test tree tells the two apart only through internal API, so
     * they are not distinguished; the message and stack trace name the exception.
     */
    FAILED,

    /** Ignored or skipped, e.g. `@Ignore`, `@Disabled` or an assumption that did not hold. */
    IGNORED,

    RUNNING,

    /** Not started (yet); known only when the framework announces its tests before running them. */
    NOT_RUN,

    /** The run ended while this test ran: it was stopped, or its process died. */
    INTERRUPTED,
}

/** Why a test did not pass. [expected] and [actual] are set when the IDE could compare them (assertEquals). */
data class TestFailure(
    val message: String?,
    val stackTrace: String?,
    val expected: String? = null,
    val actual: String? = null,
)

/**
 * One test: a leaf of the IDE's test tree. [suite] names the enclosing suites from the outside in, without the root
 * (for JUnit: the class, nested classes, the parameterized method). [locationUrl] is the IDE's location URL, e.g.
 * `java:test://pkg.FooTest/check`. [failure] is set for failed tests (and for others when the IDE has a message).
 */
data class TestCase(
    val name: String,
    val suite: List<String>,
    val locationUrl: String?,
    val outcome: TestOutcome,
    val durationMillis: Long?,
    val failure: TestFailure?,
)

/**
 * The test results of one process at one moment. [cases] are in the order of the IDE's tree. [finished]: the IDE
 * reported the end of testing; until then the results are partial. [problem]: a failure the IDE reported for the run
 * as a whole rather than for a test.
 */
data class TestRun(
    val cases: List<TestCase>,
    val finished: Boolean,
    val durationMillis: Long?,
    val problem: TestFailure? = null,
)

/** Where a test is declared: the path as the official tools write it (relative to the project root) and its line. */
data class SourcePosition(val path: String, val line: Int)

/** Which tests `tests` lists. */
enum class TestFilter {
    FAILED,
    PASSED,
    IGNORED,
    ALL,
    ;

    fun accepts(outcome: TestOutcome): Boolean = when (this) {
        FAILED -> outcome == TestOutcome.FAILED
        PASSED -> outcome == TestOutcome.PASSED
        IGNORED -> outcome == TestOutcome.IGNORED
        ALL -> true
    }
}
