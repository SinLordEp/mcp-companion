package com.github.sinlordep.mcpcompanion.localhistory

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** `mc_local_history` over a fake history: arguments, targets and which revert path each target takes. */
class LocalHistoryControlTest {

    private val zone: ZoneId = ZoneOffset.ofHours(1)

    private class FakeHistory : LocalHistoryGateway {
        val labels = mutableListOf<HistoryLabel>()
        val calls = mutableListOf<String>()
        val directories = mutableSetOf("src")
        var enabled = true

        override suspend fun putLabel(name: String): ToolResult<HistoryLabel> {
            if (!enabled) return ToolResult.failure(ErrorCode.UNSUPPORTED, "disabled")
            return HistoryLabel("id${labels.size}", name, 1_000L * (labels.size + 1)).also { labels.add(0, it) }
                .let { ToolResult.success(it) }
        }

        override suspend fun labels(): List<HistoryLabel> = labels

        override suspend fun compare(
            path: String,
            target: HistoryTarget,
            direction: DiffDirection,
            maxLines: Int,
        ): ToolResult<HistoryComparison> {
            calls += "compare $path ${target.pointInTime} $direction $maxLines"
            return ToolResult.success(HistoryComparison(path, emptyList(), 1, true))
        }

        override suspend fun restoreFile(
            path: String,
            target: HistoryTarget,
            description: String,
        ): ToolResult<RevertAttempt> {
            calls += "restore $path ${target.pointInTime} $description"
            return ToolResult.success(RevertAttempt.Reverted("cmd", listOf(path)))
        }

        override suspend fun revertToLabel(path: String, label: HistoryLabel): ToolResult<RevertAttempt> {
            calls += "revertToLabel $path ${label.id}"
            return ToolResult.success(RevertAttempt.Reverted(null, null))
        }

        override suspend fun isDirectory(path: String): ToolResult<Boolean> = if (path ==
            "gone"
        ) {
            ToolResult.failure(ErrorCode.NOT_FOUND, "none")
        } else {
            ToolResult.success(path in directories)
        }
    }

    private val history = FakeHistory()
    private val control = LocalHistoryControl(history, zone)

    private fun call(action: LocalHistoryAction, arguments: LocalHistoryArguments) =
        runBlocking { control.execute(action, arguments) }

    private fun code(action: LocalHistoryAction, arguments: LocalHistoryArguments) =
        (call(action, arguments) as ToolResult.Failure).error.code

    @Test
    fun `labels are put with a name and listed newest first`() {
        val put = call(LocalHistoryAction.LABEL, LocalHistoryArguments(name = " before refactoring "))
        assertEquals(
            "before refactoring",
            ((put as ToolResult.Success).value as LocalHistoryOutcome.Labelled).label.name,
        )
        call(LocalHistoryAction.LABEL, LocalHistoryArguments(name = "second"))
        val listed = (call(LocalHistoryAction.LABELS, LocalHistoryArguments()) as ToolResult.Success).value
        assertEquals(
            listOf("second", "before refactoring"),
            (listed as LocalHistoryOutcome.Labels).labels.map {
                it.name
            },
        )
    }

    @Test
    fun `label names are required and bounded, disabled history passes through`() {
        assertEquals(ErrorCode.INVALID_ARGUMENT, code(LocalHistoryAction.LABEL, LocalHistoryArguments(name = " ")))
        assertEquals(ErrorCode.INVALID_ARGUMENT, code(LocalHistoryAction.LABEL, LocalHistoryArguments()))
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            code(LocalHistoryAction.LABEL, LocalHistoryArguments(name = "x".repeat(201))),
        )
        assertEquals(ErrorCode.INVALID_ARGUMENT, code(LocalHistoryAction.LABEL, LocalHistoryArguments(path = "a")))
        history.enabled = false
        assertEquals(ErrorCode.UNSUPPORTED, code(LocalHistoryAction.LABEL, LocalHistoryArguments(name = "x")))
    }

    @Test
    fun `diff and preview compare at the label's time or the given time, in their direction`() {
        history.labels += HistoryLabel("old", "safe", 500)
        history.labels.add(0, HistoryLabel("new", "safe", 900))
        call(LocalHistoryAction.DIFF, LocalHistoryArguments(path = "A.java", label = "safe"))
        call(
            LocalHistoryAction.PREVIEW,
            LocalHistoryArguments(path = "A.java", time = "1970-01-01T00:00:01Z", maxLines = 7),
        )
        call(LocalHistoryAction.DIFF, LocalHistoryArguments(path = "A.java", time = "1970-01-01T01:00:02"))
        call(LocalHistoryAction.DIFF, LocalHistoryArguments(path = "A.java", time = "3000"))
        assertEquals(
            listOf(
                "compare A.java 900 SINCE_THEN 400",
                "compare A.java 1000 BACK_TO_THEN 7",
                "compare A.java 2000 SINCE_THEN 400",
                "compare A.java 3000 SINCE_THEN 400",
            ),
            history.calls,
        )
    }

    @Test
    fun `a target is exactly one known label or a valid time`() {
        fun diff(arguments: LocalHistoryArguments) = code(LocalHistoryAction.DIFF, arguments)
        assertEquals(ErrorCode.INVALID_ARGUMENT, diff(LocalHistoryArguments(path = "A.java")))
        assertEquals(ErrorCode.INVALID_ARGUMENT, diff(LocalHistoryArguments(path = "A.java", label = "x", time = "1")))
        assertEquals(ErrorCode.NOT_FOUND, diff(LocalHistoryArguments(path = "A.java", label = "unknown")))
        assertEquals(ErrorCode.INVALID_ARGUMENT, diff(LocalHistoryArguments(path = "A.java", time = "yesterday")))
        assertEquals(ErrorCode.INVALID_ARGUMENT, diff(LocalHistoryArguments(time = "1")))
        assertEquals(ErrorCode.INVALID_ARGUMENT, diff(LocalHistoryArguments(path = "A.java", time = "1", maxLines = 0)))
        assertEquals(
            ErrorCode.INVALID_ARGUMENT,
            code(LocalHistoryAction.REVERT, LocalHistoryArguments(path = "A.java", time = "1", maxLines = 5)),
        )
        assertTrue(history.calls.isEmpty())
    }

    @Test
    fun `revert restores a file, reverts a directory to a label, and refuses a directory at a time`() {
        history.labels += HistoryLabel("L1", "safe", 500)
        call(LocalHistoryAction.REVERT, LocalHistoryArguments(path = "A.java", label = "safe"))
        val local = LocalDateTime.of(2026, 11, 19, 10, 15)
        call(LocalHistoryAction.REVERT, LocalHistoryArguments(path = "A.java", time = local.toString()))
        call(LocalHistoryAction.REVERT, LocalHistoryArguments(path = "src", label = "safe"))
        assertEquals(
            listOf(
                "restore A.java 500 'safe'",
                "restore A.java ${local.atZone(zone).toInstant().toEpochMilli()} 2026-11-19T10:15",
                "revertToLabel src L1",
            ),
            history.calls,
        )
        assertEquals(
            ErrorCode.UNSUPPORTED,
            code(LocalHistoryAction.REVERT, LocalHistoryArguments(path = "src", time = "1")),
        )
        assertEquals(
            ErrorCode.NOT_FOUND,
            code(LocalHistoryAction.REVERT, LocalHistoryArguments(path = "gone", time = "1")),
        )
    }
}
