package com.github.sinlordep.mcpcompanion.toolsets.structuralsearch

import com.github.sinlordep.mcpcompanion.structuralsearch.StructuralSearchAction
import com.github.sinlordep.mcpcompanion.structuralsearch.VariableConstraint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** MCP input values of `action`. */
@Serializable
enum class StructuralSearchActionInput(val action: StructuralSearchAction) {
    @SerialName("search")
    SEARCH(StructuralSearchAction.SEARCH),

    @SerialName("templates")
    TEMPLATES(StructuralSearchAction.TEMPLATES),
}

/** One variable constraint as clients write it; see the tool description. */
@Serializable
data class VariableConstraintInput(
    val name: String,
    val text: String? = null,
    val textInverted: Boolean? = null,
    val type: String? = null,
    val typeInverted: Boolean? = null,
    val withinHierarchy: Boolean? = null,
    val minCount: Int? = null,
    val maxCount: Int? = null,
    val target: Boolean? = null,
) {
    fun toConstraint(): VariableConstraint = VariableConstraint(
        name = name,
        text = text,
        textInverted = textInverted,
        type = type,
        typeInverted = typeInverted,
        withinHierarchy = withinHierarchy,
        minCount = minCount,
        maxCount = maxCount,
        target = target,
    )
}
