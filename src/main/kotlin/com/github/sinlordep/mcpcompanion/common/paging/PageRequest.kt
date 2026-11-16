package com.github.sinlordep.mcpcompanion.common.paging

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/**
 * Which slice of a result list a client asks for. The offset is the cursor: a client continues with
 * [Page.nextOffset]. Create instances through [of], which validates client input.
 */
@ConsistentCopyVisibility
data class PageRequest private constructor(val limit: Int, val offset: Int) {

    companion object {
        const val DEFAULT_LIMIT: Int = 100
        const val MAX_LIMIT: Int = 1_000

        val FIRST: PageRequest = PageRequest(DEFAULT_LIMIT, 0)

        fun of(limit: Int?, offset: Int?): ToolResult<PageRequest> {
            val effectiveLimit = limit ?: DEFAULT_LIMIT
            val effectiveOffset = offset ?: 0
            return when {
                effectiveLimit !in 1..MAX_LIMIT -> ToolResult.failure(
                    ErrorCode.INVALID_ARGUMENT,
                    "limit must be between 1 and $MAX_LIMIT, was $effectiveLimit",
                    "Retry with a limit in range, or omit it to get $DEFAULT_LIMIT items.",
                )

                effectiveOffset < 0 -> ToolResult.failure(
                    ErrorCode.INVALID_ARGUMENT,
                    "offset must not be negative, was $effectiveOffset",
                    "Use 0 for the first page, then the nextOffset of the previous page.",
                )

                else -> ToolResult.success(PageRequest(effectiveLimit, effectiveOffset))
            }
        }
    }
}
