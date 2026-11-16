package com.github.sinlordep.mcpcompanion.toolsets.usages

import com.github.sinlordep.mcpcompanion.navigation.usages.AccessFilter
import com.github.sinlordep.mcpcompanion.navigation.usages.AccessKind
import com.github.sinlordep.mcpcompanion.navigation.usages.FoundUsages
import com.github.sinlordep.mcpcompanion.navigation.usages.Usage
import com.github.sinlordep.mcpcompanion.toolsets.symbols.OriginOutput
import com.github.sinlordep.mcpcompanion.toolsets.symbols.SymbolOutput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** MCP input values of `access`. */
@Serializable
enum class AccessInput(val filter: AccessFilter) {
    @SerialName("any")
    ANY(AccessFilter.ANY),

    @SerialName("read")
    READ(AccessFilter.READ),

    @SerialName("write")
    WRITE(AccessFilter.WRITE),
}

/** MCP output model of `mc_find_usages`. */
@Serializable
data class FindUsagesOutput(
    val symbol: SymbolOutput,
    val usages: List<UsageOutput>,
    val hasMore: Boolean,
    val nextOffset: Int? = null,
) {
    companion object {
        fun from(found: FoundUsages): FindUsagesOutput = FindUsagesOutput(
            symbol = SymbolOutput.from(found.target),
            usages = found.usages.items.map(UsageOutput::from),
            hasMore = found.usages.hasMore,
            nextOffset = found.usages.nextOffset,
        )
    }
}

@Serializable
data class UsageOutput(
    val path: String,
    val line: Int,
    val column: Int,
    val lineText: String,
    val enclosing: String? = null,
    val access: AccessOutput? = null,
    val origin: OriginOutput,
) {
    @Serializable
    enum class AccessOutput {
        @SerialName("read")
        READ,

        @SerialName("write")
        WRITE,

        @SerialName("read_write")
        READ_WRITE,
    }

    companion object {
        fun from(usage: Usage): UsageOutput = UsageOutput(
            path = usage.site.location.path,
            line = usage.site.location.line,
            column = usage.site.location.column,
            lineText = usage.site.lineText,
            enclosing = usage.site.enclosingDeclaration,
            access = when (usage.access) {
                AccessKind.READ -> AccessOutput.READ
                AccessKind.WRITE -> AccessOutput.WRITE
                AccessKind.READ_WRITE -> AccessOutput.READ_WRITE
                null -> null
            },
            origin = OriginOutput.of(usage.site.location.origin),
        )
    }
}
