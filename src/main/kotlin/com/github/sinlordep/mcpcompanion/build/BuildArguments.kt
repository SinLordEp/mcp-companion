package com.github.sinlordep.mcpcompanion.build

import com.github.sinlordep.mcpcompanion.common.actions.ToolArguments

/** The actions of `mc_build`. */
enum class BuildAction { BUILD, STATUS }

/** The optional arguments of `mc_build`; each action accepts a subset. */
enum class BuildParameter(val label: String) {
    MODULE("module"),
    INCLUDE_DEPENDENTS("includeDependents"),
    INCLUDE_WARNINGS("includeWarnings"),
    TIMEOUT("timeoutSeconds"),
}

/** Raw client arguments of one `mc_build` call, not yet validated. Null means "not given". */
data class BuildArguments(
    val module: String? = null,
    val includeDependents: Boolean? = null,
    val includeWarnings: Boolean? = null,
    val timeoutSeconds: Int? = null,
) : ToolArguments<BuildParameter> {

    override fun given(): Set<BuildParameter> = buildSet {
        if (module != null) add(BuildParameter.MODULE)
        if (includeDependents != null) add(BuildParameter.INCLUDE_DEPENDENTS)
        if (includeWarnings != null) add(BuildParameter.INCLUDE_WARNINGS)
        if (timeoutSeconds != null) add(BuildParameter.TIMEOUT)
    }
}
