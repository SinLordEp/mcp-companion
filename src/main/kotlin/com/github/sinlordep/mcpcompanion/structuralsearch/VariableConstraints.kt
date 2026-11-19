package com.github.sinlordep.mcpcompanion.structuralsearch

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import java.util.regex.PatternSyntaxException

/**
 * Checks the clients' variable constraints before the IDE sees them: names as template variables are written, each
 * name once, counts in range, text patterns that compile. A `maxCount` of [StructuralSearchArguments.UNBOUNDED_INPUT]
 * becomes [VariableConstraint.UNBOUNDED].
 */
internal object VariableConstraints {

    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    const val MAX_CONSTRAINTS: Int = 50
    const val MAX_COUNT: Int = 10_000

    fun validate(constraints: List<VariableConstraint>): ToolResult<List<VariableConstraint>> {
        if (constraints.size > MAX_CONSTRAINTS) {
            return invalid("At most $MAX_CONSTRAINTS constraints, got ${constraints.size}.")
        }
        val normalised = constraints.map(::normalised)
        normalised.firstNotNullOfOrNull(::problemOf)?.let { return invalid(it) }
        val duplicate = normalised.groupBy { it.name }.filterValues { it.size > 1 }.keys.firstOrNull()
        return if (duplicate != null) {
            invalid("Variable '$duplicate' has more than one constraint.")
        } else {
            ToolResult.success(normalised)
        }
    }

    /** The name without dollars and surrounding blanks, and "no limit" as [VariableConstraint.UNBOUNDED]. */
    private fun normalised(constraint: VariableConstraint): VariableConstraint = constraint.copy(
        name = constraint.name.trim().removePrefix("$").removeSuffix("$"),
        maxCount = constraint.maxCount?.let {
            if (it == StructuralSearchArguments.UNBOUNDED_INPUT) VariableConstraint.UNBOUNDED else it
        },
    )

    private fun problemOf(constraint: VariableConstraint): String? {
        val name = constraint.name
        return when {
            !NAME.matches(name) -> "'$name' is not a variable name."

            constraint.type != null && constraint.type.isBlank() -> "$name: type is empty."

            else -> countProblem(name, constraint.minCount, constraint.maxCount)
                ?: textPatternError(constraint.text)?.let { "$name: text is not a regular expression: $it" }
        }
    }

    private fun countProblem(name: String, min: Int?, max: Int?): String? = when {
        min != null && min !in 0..MAX_COUNT -> "$name: minCount must be between 0 and $MAX_COUNT."

        max != null && max != VariableConstraint.UNBOUNDED && max !in 1..MAX_COUNT ->
            "$name: maxCount must be between 1 and $MAX_COUNT, or -1 for no limit."

        min != null && max != null && min > max -> "$name: minCount $min is greater than maxCount $max."

        else -> null
    }

    private fun textPatternError(text: String?): String? = try {
        text?.let(::Regex)
        null
    } catch (e: PatternSyntaxException) {
        e.description
    }

    private fun invalid(message: String): ToolResult<Nothing> = ToolResult.failure(
        ErrorCode.INVALID_ARGUMENT,
        message,
        "Each constraint names a variable of the template (Name for \$Name\$) once; minCount 0-$MAX_COUNT, " +
            "maxCount 1-$MAX_COUNT or -1 for no limit; text is a Java regular expression.",
    )
}
