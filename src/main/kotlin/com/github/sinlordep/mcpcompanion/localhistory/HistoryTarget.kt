package com.github.sinlordep.mcpcompanion.localhistory

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Which past state `diff`, `preview` and `revert` mean, named in exactly one way: the newest label of this tool with
 * a name, or a point in time.
 */
sealed interface HistoryTarget {
    data class AtLabel(val label: HistoryLabel) : HistoryTarget

    data class AtTime(val epochMillis: Long) : HistoryTarget

    companion object {
        /**
         * The target the arguments name, a label looked up in [labels] (newest first); times without an offset are in
         * [zone] (the IDE's time zone).
         */
        fun of(arguments: LocalHistoryArguments, labels: List<HistoryLabel>, zone: ZoneId): ToolResult<HistoryTarget> {
            val given = listOfNotNull(arguments.label, arguments.time)
            if (given.size != 1) {
                return ToolResult.failure(
                    ErrorCode.INVALID_ARGUMENT,
                    "Pass exactly one of label and time (got ${given.size}).",
                    "label: the name of a label put with action=label; time: e.g. 2026-11-19T10:15:00.",
                )
            }
            arguments.label?.let { name ->
                return labels.firstOrNull { it.name == name }?.let { ToolResult.success(AtLabel(it)) }
                    ?: ToolResult.failure(
                        ErrorCode.NOT_FOUND,
                        "No label '$name' put by this tool is kept in the Local History.",
                        "action=labels lists them; labels put in the IDE itself are not visible here, use time.",
                    )
            }
            val text = checkNotNull(arguments.time).trim()
            return parseTime(text, zone)?.let { ToolResult.success(AtTime(it)) } ?: ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "time '$text' is not a time.",
                "Use ISO-8601: 2026-11-19T10:15:00 (the IDE's time zone), 2026-11-19T10:15:00Z, or epoch ms.",
            )
        }

        private fun parseTime(text: String, zone: ZoneId): Long? = text.toLongOrNull()
            ?: attempt { Instant.parse(text).toEpochMilli() }
            ?: attempt { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            ?: attempt { LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli() }

        private inline fun attempt(parse: () -> Long): Long? = try {
            parse()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

/** The point in time a target means. */
val HistoryTarget.pointInTime: Long
    get() = when (this) {
        is HistoryTarget.AtLabel -> label.timestamp
        is HistoryTarget.AtTime -> epochMillis
    }
