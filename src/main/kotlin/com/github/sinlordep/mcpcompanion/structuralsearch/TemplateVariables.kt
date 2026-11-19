package com.github.sinlordep.mcpcompanion.structuralsearch

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/** The variables of a search template: `$Name$` (Structural Search's syntax), in the order they first appear. */
object TemplateVariables {

    private val VARIABLE = Regex("\\$([A-Za-z_][A-Za-z0-9_]*)\\$")

    fun namesIn(pattern: String): List<String> = VARIABLE.findAll(pattern).map { it.groupValues[1] }.distinct().toList()

    /** A failure when a constraint names a variable that [pattern] does not contain. */
    fun checkConstrained(pattern: String, constraints: List<VariableConstraint>): ToolResult<Unit> {
        val known = namesIn(pattern)
        val unknown = constraints.map { it.name }.filterNot { it in known }
        return if (unknown.isEmpty()) {
            ToolResult.success(Unit)
        } else {
            ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "The template has no variable ${unknown.joinToString { "\$$it\$" }}.",
                if (known.isEmpty()) {
                    "Its variables are written \$Name\$; this template has none."
                } else {
                    "Its variables: ${known.joinToString { "\$$it\$" }}; constrain them by name without the dollars."
                },
            )
        }
    }
}
