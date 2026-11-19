package com.github.sinlordep.mcpcompanion.springbeans

import com.github.sinlordep.mcpcompanion.common.actions.ToolArguments

/** The actions of `mc_spring_beans`. */
enum class SpringBeansAction { LIST, DEPENDENCIES }

/** The arguments of `mc_spring_beans` besides `action`; each action accepts a subset. */
enum class SpringBeansParameter(val label: String) {
    MODULE("module"),
    NAME("name"),
    NAME_PATTERN("namePattern"),
    TYPE("type"),
    INCLUDE_LIBRARIES("includeLibraries"),
    BEAN("bean"),
    QUALIFIED_NAME("qualifiedName"),
    PATH("path"),
    LINE("line"),
    LIMIT("limit"),
    OFFSET("offset"),
}

/** Raw client arguments of one `mc_spring_beans` call, not yet validated. Null means "not given". */
data class SpringBeansArguments(
    val module: String? = null,
    val name: String? = null,
    val namePattern: String? = null,
    val type: String? = null,
    val includeLibraries: Boolean? = null,
    val bean: String? = null,
    val qualifiedName: String? = null,
    val path: String? = null,
    val line: Int? = null,
    val limit: Int? = null,
    val offset: Int? = null,
) : ToolArguments<SpringBeansParameter> {

    override fun given(): Set<SpringBeansParameter> = buildSet {
        val values = mapOf(
            SpringBeansParameter.MODULE to module,
            SpringBeansParameter.NAME to name,
            SpringBeansParameter.NAME_PATTERN to namePattern,
            SpringBeansParameter.TYPE to type,
            SpringBeansParameter.INCLUDE_LIBRARIES to includeLibraries,
            SpringBeansParameter.BEAN to bean,
            SpringBeansParameter.QUALIFIED_NAME to qualifiedName,
            SpringBeansParameter.PATH to path,
            SpringBeansParameter.LINE to line,
            SpringBeansParameter.LIMIT to limit,
            SpringBeansParameter.OFFSET to offset,
        )
        values.forEach { (parameter, value) -> if (value != null) add(parameter) }
    }
}
