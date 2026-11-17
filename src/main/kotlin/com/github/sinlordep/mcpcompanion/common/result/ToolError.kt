package com.github.sinlordep.mcpcompanion.common.result

/**
 * A business failure reported to the MCP client.
 *
 * [message] says what went wrong; [nextStep] tells the AI agent what to do about it. Both are written for the agent,
 * in English, like tool descriptions; they are not UI text.
 */
data class ToolError(val code: ErrorCode, val message: String, val nextStep: String? = null)

/** Stable, machine-readable failure categories. Clients may branch on them, so never rename a constant. */
enum class ErrorCode {
    /** An argument is missing, malformed or out of range. */
    INVALID_ARGUMENT,

    /** The file, symbol or other target does not exist. */
    NOT_FOUND,

    /** The query matches more than one target. */
    AMBIGUOUS,

    /** This IDE cannot answer the query, e.g. a language plugin is not installed. */
    UNSUPPORTED,

    /**
     * The request conflicts with the current code, e.g. a safe delete found usages, or the code changed while the
     * tool ran. Nothing was changed.
     */
    CONFLICT,

    /**
     * The user did not allow the action: they refused it when the IDE asked, or did not answer in time. Nothing was
     * done. Agents must not retry on their own; they ask the user first.
     */
    DENIED,
}
