package com.github.sinlordep.mcpcompanion.common.source

/**
 * Cheap identity and position of a search hit: the file's URL and an offset in it. Searches report hits in no stable
 * order and describing a hit fully (document, line text, enclosing declarations, path) is expensive, so use cases
 * sort and page on keys, and gateways describe only the hits of the page.
 */
data class SourceKey(val file: String, val offset: Int) {
    companion object {
        /** By file, then offset: reading order within a file, a fixed order between files. */
        val ORDER: Comparator<SourceKey> = compareBy({ it.file }, { it.offset })
    }
}
