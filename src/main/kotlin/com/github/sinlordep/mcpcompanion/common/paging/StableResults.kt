package com.github.sinlordep.mcpcompanion.common.paging

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/**
 * Pages the results of a search whose order is not stable. The platform searches files in parallel, so the same
 * search reports its results in a different order on every run (seen end to end: page 1 and page 2 of
 * `mc_find_usages` overlapped). Paging by offset is only correct over a fixed order, so the whole result, bounded by
 * [MAX_RESULTS], is sorted before the page is cut.
 */
object StableResults {

    /** Upper bound of results one search may produce; gateways stop searching after `MAX_RESULTS + 1`. */
    const val MAX_RESULTS: Int = 10_000

    /** What gateways are asked for: one more than [MAX_RESULTS], to notice when there are too many. */
    const val SEARCH_LIMIT: Int = MAX_RESULTS + 1

    /**
     * The [results] that [keep] lets through, sorted by [order] and cut to [request]; a failure when the search hit
     * [SEARCH_LIMIT] (counted before filtering, since the search itself was cut short).
     */
    fun <T> page(
        results: List<T>,
        order: Comparator<in T>,
        request: PageRequest,
        keep: (T) -> Boolean = { true },
    ): ToolResult<Page<T>> = if (results.size > MAX_RESULTS) {
        ToolResult.failure(
            ErrorCode.INVALID_ARGUMENT,
            "The search found more than $MAX_RESULTS results, too many to page in a stable order.",
            "Narrow the search with scope (module:<name> or dir:<path>).",
        )
    } else {
        ToolResult.success(results.filter(keep).sortedWith(order).asSequence().page(request))
    }
}

/**
 * Collects the results of a push-style platform search, which may call from several threads, until [limit] results
 * are in. [offer] returns false from then on, so the search can stop.
 */
class BoundedCollector<T>(private val limit: Int) {

    private val items = ArrayList<T>()

    @Synchronized
    fun offer(item: T): Boolean {
        if (items.size < limit) items += item
        return items.size < limit
    }

    @Synchronized
    fun items(): List<T> = items.toList()
}
