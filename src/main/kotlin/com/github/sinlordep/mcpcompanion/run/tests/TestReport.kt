package com.github.sinlordep.mcpcompanion.run.tests

import com.github.sinlordep.mcpcompanion.common.paging.Page

/** What `tests` reports about one process. [wait] is set when the caller asked to wait. */
sealed interface TestReport {
    val wait: TestWait?

    /**
     * The IDE has test results for the process. [items] are the tests the filter lets through, one page of them.
     * [hint] says where to look when the results alone do not explain the verdict (a run without tests).
     */
    data class Results(
        val summary: TestSummary,
        val hint: String?,
        val problem: BoundedFailure?,
        val filter: TestFilter,
        val items: Page<ReportedTest>,
        override val wait: TestWait?,
    ) : TestReport

    /** The IDE has no test results for the process; [reason] says why and what to do. */
    data class Unavailable(val notStarted: Boolean, val reason: String, override val wait: TestWait?) : TestReport
}

/** How long `tests` waited for the run to finish; [timedOut] when it had not finished by then. */
data class TestWait(val waitedMillis: Long, val timedOut: Boolean)

/** One listed test. [position] and [failure] only for failed tests ([failure] also for ignored ones with a message). */
data class ReportedTest(val case: TestCase, val position: SourcePosition?, val failure: BoundedFailure?)

/** A [TestFailure] cut to size; [truncated] when any of its texts was shortened. */
data class BoundedFailure(
    val message: String?,
    val stackTrace: String?,
    val expected: String?,
    val actual: String?,
    val truncated: Boolean,
) {
    companion object {
        /** Longest message, expected or actual value kept, in characters. */
        const val MAX_TEXT: Int = 4_000

        /** Longest stack trace kept, in characters, whatever the number of lines. */
        const val MAX_STACK_TRACE: Int = 20_000

        /** At most [stackLines] lines of the stack trace, and the size limits above. */
        fun of(failure: TestFailure, stackLines: Int): BoundedFailure {
            var truncated = false
            fun cut(text: String?, max: Int): String? {
                if (text == null || text.length <= max) return text
                truncated = true
                return text.take(max)
            }
            // JUnit's traces start with an empty line where the message was; it would take one of the stackLines.
            val lines = failure.stackTrace?.trimStart('\r', '\n')?.trimEnd()?.lines()
            val keptLines = lines?.take(stackLines)?.joinToString("\n")?.ifEmpty { null }
            if (lines != null && lines.size > stackLines) truncated = true
            return BoundedFailure(
                message = cut(failure.message, MAX_TEXT),
                stackTrace = cut(keptLines, MAX_STACK_TRACE),
                expected = cut(failure.expected, MAX_TEXT),
                actual = cut(failure.actual, MAX_TEXT),
                truncated = truncated,
            )
        }
    }
}
