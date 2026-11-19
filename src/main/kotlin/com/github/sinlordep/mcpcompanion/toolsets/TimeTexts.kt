package com.github.sinlordep.mcpcompanion.toolsets

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** How tool output writes points in time: ISO-8601 with the offset of the IDE's time zone, to the second. */
object TimeTexts {

    fun iso(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).truncatedTo(ChronoUnit.SECONDS).atZone(zone).toOffsetDateTime().toString()
}
