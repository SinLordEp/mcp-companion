package com.github.sinlordep.mcpcompanion.changesignature

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult

/** Visibility a method can be given. */
enum class Visibility { PUBLIC, PROTECTED, PACKAGE_PRIVATE, PRIVATE }

/** Which parameter of the current signature a new parameter continues: by 0-based position or by name. */
sealed interface ParameterRef {
    data class Position(val index: Int) : ParameterRef

    data class Name(val name: String) : ParameterRef

    companion object {
        /** A 0-based position when [text] is a number, otherwise a parameter name. */
        fun parse(text: String): ParameterRef = text.toIntOrNull()?.let(::Position) ?: Name(text)
    }
}

/**
 * One parameter of the new signature. [from] is the current parameter it continues (its arguments stay in place at
 * every call site); null for a new parameter, whose [defaultValue] is passed at every existing call site. [name] and
 * [type] are null to keep those of [from].
 */
data class ParameterChange(val name: String?, val type: String?, val from: ParameterRef?, val defaultValue: String?)

/**
 * The new signature of a method: [parameters] in their new order (null: unchanged), [returnType] (null: unchanged),
 * [visibility] (null: unchanged). The method's name stays; renaming is another refactoring.
 */
@ConsistentCopyVisibility
data class SignatureChange private constructor(
    val parameters: List<ParameterChange>?,
    val returnType: String?,
    val visibility: Visibility?,
) {
    companion object {
        /**
         * Validates client input as far as it does not depend on the method: something must change; a new parameter
         * needs a name, a type and a default value; an existing one takes no default value and is used once; names
         * are unique. Blank strings count as absent.
         */
        fun of(
            parameters: List<ParameterChange>?,
            returnType: String?,
            visibility: Visibility?,
        ): ToolResult<SignatureChange> {
            val cleaned = parameters?.map { it.cleaned() }
            val type = returnType?.trim()?.takeIf { it.isNotEmpty() }
            val problem = when {
                cleaned == null && type == null && visibility == null -> NOTHING

                cleaned == null -> null

                else -> cleaned.withIndex().firstNotNullOfOrNull { (index, it) -> problemOf(index, it) }
                    ?: duplicates(cleaned)
            }
            return if (problem == null) {
                ToolResult.success(SignatureChange(cleaned, type, visibility))
            } else {
                ToolResult.failure(ErrorCode.INVALID_ARGUMENT, problem, if (problem == NOTHING) WHAT else HOW)
            }
        }

        private const val NOTHING = "Nothing to change."
        private const val WHAT =
            "Pass parameters (the complete new list), returnType or visibility; rename methods with rename_refactoring."
        private const val HOW = "Each parameter is {from} to keep one, {from, name, type} to change it, or " +
            "{name, type, defaultValue} for a new one."

        private fun ParameterChange.cleaned() = ParameterChange(
            name = name?.trim()?.takeIf { it.isNotEmpty() },
            type = type?.trim()?.takeIf { it.isNotEmpty() },
            from = from,
            defaultValue = defaultValue?.trim()?.takeIf { it.isNotEmpty() },
        )

        private fun problemOf(index: Int, parameter: ParameterChange): String? =
            if (parameter.from == null) newProblem(index, parameter) else keptProblem(index, parameter)

        private fun keptProblem(index: Int, parameter: ParameterChange): String? = when {
            parameter.from is ParameterRef.Position && parameter.from.index < 0 ->
                "parameters[$index]: from must be a name or a position from 0."

            parameter.defaultValue != null ->
                "parameters[$index]: defaultValue is only for new parameters (without from)."

            else -> null
        }

        private fun newProblem(index: Int, parameter: ParameterChange): String? = when {
            parameter.name == null || parameter.type == null ->
                "parameters[$index]: a new parameter needs name and type."

            parameter.defaultValue == null ->
                "parameters[$index] (${parameter.name}): a new parameter needs a defaultValue, the expression passed " +
                    "at existing call sites."

            else -> null
        }

        private fun duplicates(parameters: List<ParameterChange>): String? {
            val twice = parameters.mapNotNull { it.from }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            val sameName = parameters.mapNotNull { it.name }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            return when {
                twice.isNotEmpty() -> "parameters: ${twice.first().describe()} is used more than once."
                sameName.isNotEmpty() -> "parameters: the name ${sameName.first()} is used more than once."
                else -> null
            }
        }

        private fun ParameterRef.describe() = when (this) {
            is ParameterRef.Position -> "the current parameter at $index"
            is ParameterRef.Name -> "the current parameter $name"
        }
    }
}
