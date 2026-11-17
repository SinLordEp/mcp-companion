package com.github.sinlordep.mcpcompanion.modules

import com.github.sinlordep.mcpcompanion.common.actions.ToolArguments
import com.github.sinlordep.mcpcompanion.common.modulegraph.GraphDirection

/** The actions of `mc_modules`. */
enum class ModulesAction { LIST, GRAPH, CYCLES }

/** How `graph` returns the edges: structured and paged (default), or as one Mermaid flowchart. */
enum class GraphFormat { JSON, MERMAID }

/** The optional arguments of `mc_modules`; each action accepts a subset. */
enum class ModulesParameter(val label: String) {
    MODULE("module"),
    DIRECTION("direction"),
    DEPTH("depth"),
    INCLUDE_LIBRARIES("includeLibraries"),
    IGNORE_TEST_SCOPE("ignoreTestScope"),
    FORMAT("format"),
    LIMIT("limit"),
    OFFSET("offset"),
}

/** Raw client arguments of one `mc_modules` call, not yet validated. Null means "not given". */
data class ModulesArguments(
    val module: String? = null,
    val direction: GraphDirection? = null,
    val depth: Int? = null,
    val includeLibraries: Boolean? = null,
    val ignoreTestScope: Boolean? = null,
    val format: GraphFormat? = null,
    val limit: Int? = null,
    val offset: Int? = null,
) : ToolArguments<ModulesParameter> {

    override fun given(): Set<ModulesParameter> = buildSet {
        if (module != null) add(ModulesParameter.MODULE)
        if (direction != null) add(ModulesParameter.DIRECTION)
        if (depth != null) add(ModulesParameter.DEPTH)
        if (includeLibraries != null) add(ModulesParameter.INCLUDE_LIBRARIES)
        if (ignoreTestScope != null) add(ModulesParameter.IGNORE_TEST_SCOPE)
        if (format != null) add(ModulesParameter.FORMAT)
        if (limit != null) add(ModulesParameter.LIMIT)
        if (offset != null) add(ModulesParameter.OFFSET)
    }
}
