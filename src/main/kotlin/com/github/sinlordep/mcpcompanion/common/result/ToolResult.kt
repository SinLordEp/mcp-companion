package com.github.sinlordep.mcpcompanion.common.result

/**
 * Outcome of a use case. Business failures are values, not exceptions; the MCP adapter layer turns a [Failure]
 * into an MCP error response in one place.
 */
sealed interface ToolResult<out T> {

    data class Success<out T>(val value: T) : ToolResult<T>

    data class Failure(val error: ToolError) : ToolResult<Nothing>

    companion object {
        fun <T> success(value: T): ToolResult<T> = Success(value)

        fun failure(code: ErrorCode, message: String, nextStep: String? = null): ToolResult<Nothing> =
            Failure(ToolError(code, message, nextStep))
    }
}

inline fun <T, R> ToolResult<T>.map(transform: (T) -> R): ToolResult<R> = when (this) {
    is ToolResult.Success -> ToolResult.Success(transform(value))
    is ToolResult.Failure -> this
}

inline fun <T, R> ToolResult<T>.flatMap(transform: (T) -> ToolResult<R>): ToolResult<R> = when (this) {
    is ToolResult.Success -> transform(value)
    is ToolResult.Failure -> this
}
