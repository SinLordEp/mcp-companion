package com.github.sinlordep.mcpcompanion.common.paging

/** One slice of a result list. [nextOffset] is null on the last page. */
data class Page<out T>(val items: List<T>, val offset: Int, val hasMore: Boolean) {
    val nextOffset: Int? get() = if (hasMore) offset + items.size else null
}

/**
 * Cuts [request]'s slice out of this sequence. Consumes at most `offset + limit + 1` elements, so an expensive
 * search can stop early; the extra element only tells whether more exist.
 */
fun <T> Sequence<T>.page(request: PageRequest): Page<T> {
    val window = drop(request.offset).take(request.limit + 1).toList()
    return Page(
        items = window.take(request.limit),
        offset = request.offset,
        hasMore = window.size > request.limit,
    )
}
