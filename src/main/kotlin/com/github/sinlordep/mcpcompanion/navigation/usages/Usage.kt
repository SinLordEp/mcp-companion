package com.github.sinlordep.mcpcompanion.navigation.usages

import com.github.sinlordep.mcpcompanion.common.source.CodeSite
import com.github.sinlordep.mcpcompanion.common.source.SourceKey

/** One reference to the searched symbol. [access] is null when the language does not tell reads from writes. */
data class Usage(val site: CodeSite, val access: AccessKind?)

/**
 * A usage before it is described: enough to sort, filter and page. [access] is only filled in when the search was
 * asked for it (an access filter is active).
 */
data class UsageKey(val source: SourceKey, val access: AccessKind?) {
    companion object {
        val ORDER: Comparator<UsageKey> = compareBy(SourceKey.ORDER) { it.source }
    }
}

/** How a usage accesses a variable-like symbol (field, property, variable, parameter). */
enum class AccessKind { READ, WRITE, READ_WRITE }

/** Which usages a client wants, by access. [READ] and [WRITE] both include [AccessKind.READ_WRITE] (e.g. `i++`). */
enum class AccessFilter {
    ANY,
    READ,
    WRITE,
    ;

    fun accepts(access: AccessKind?): Boolean = when (this) {
        ANY -> true
        READ -> access == AccessKind.READ || access == AccessKind.READ_WRITE
        WRITE -> access == AccessKind.WRITE || access == AccessKind.READ_WRITE
    }
}
