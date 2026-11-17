package com.github.sinlordep.mcpcompanion.yield

/**
 * Which official MCP tools replace which of our toolsets: toolset id -> official tool names.
 *
 * The shipped data lives in [RESOURCE], one toolset per line, so adding an equivalence is a one-line data change:
 *
 * ```
 * # comment
 * navigation = find_usages, find_implementations
 * ```
 */
class EquivalenceCatalog(entries: Map<String, Set<String>>) {

    private val entries: Map<String, Set<String>> = entries.toMap()

    /** Official tool names that replace the toolset [toolsetId]; empty when none is known. */
    fun equivalentsOf(toolsetId: String): Set<String> = entries[toolsetId].orEmpty()

    companion object {
        const val RESOURCE: String = "/mcp-companion/official-equivalents.txt"

        /** The catalog shipped with the plugin. */
        fun bundled(): EquivalenceCatalog {
            val text = checkNotNull(EquivalenceCatalog::class.java.getResource(RESOURCE)) { "Missing $RESOURCE" }
                .readText()
            return parse(text)
        }

        /** Parses the [RESOURCE] format. Throws on malformed lines: the data ships with the plugin and is tested. */
        fun parse(text: String): EquivalenceCatalog {
            val entries = mutableMapOf<String, Set<String>>()
            text.lineSequence()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .forEach { line ->
                    val toolsetId = line.substringBefore('=', missingDelimiterValue = "").trim()
                    require(toolsetId.isNotEmpty()) { "Expected 'toolsetId = tool, tool' but got '$line'" }
                    require(toolsetId !in entries) { "Toolset '$toolsetId' is listed twice" }
                    entries[toolsetId] = line.substringAfter('=').split(',')
                        .map { it.trim() }
                        .filterTo(linkedSetOf()) { it.isNotEmpty() }
                }
            return EquivalenceCatalog(entries)
        }
    }
}
