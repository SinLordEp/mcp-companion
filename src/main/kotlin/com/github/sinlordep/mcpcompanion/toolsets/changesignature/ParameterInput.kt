package com.github.sinlordep.mcpcompanion.toolsets.changesignature

import com.github.sinlordep.mcpcompanion.changesignature.ParameterChange
import com.github.sinlordep.mcpcompanion.changesignature.ParameterRef
import kotlinx.serialization.Serializable

/**
 * One parameter of the new signature as clients write it. [from] is a current parameter's name or 0-based position
 * (as a string); without it the parameter is new.
 */
@Serializable
data class ParameterInput(
    val name: String? = null,
    val type: String? = null,
    val from: String? = null,
    val defaultValue: String? = null,
) {
    fun toChange(): ParameterChange = ParameterChange(
        name = name,
        type = type,
        from = from?.trim()?.takeIf { it.isNotEmpty() }?.let(ParameterRef::parse),
        defaultValue = defaultValue,
    )
}
