package com.github.sinlordep.mcpcompanion.common.paging

/**
 * One slice of a result list. [nextOffset] is where the next page starts, null on the last page; it normally follows
 * from [offset] and the number of items, but stays put when items are dropped after paging (see [mapNotNull]).
 */
data class Page<out T>(
    val items: List<T>,
    val offset: Int,
    val hasMore: Boolean,
    val nextOffset: Int? = if (hasMore) offset + items.size else null,
)

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

/** The same page with each item transformed; items that turn into null are dropped without moving the cursor. */
fun <T, R : Any> Page<T>.mapNotNull(transform: (T) -> R?): Page<R> =
    Page(items.mapNotNull(transform), offset, hasMore, nextOffset)
