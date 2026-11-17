package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.time.Clock
import com.github.sinlordep.mcpcompanion.common.time.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The user's permission for one launch, as the bundled MCP Server asks for `execute_run_configuration`: unless the
 * user turned confirmation off, the IDE asks, and only an Allow lets the launch go on. "Launch without confirmation"
 * takes effect only together with Allow. A question nobody answers within [timeout] counts as not allowed, so the MCP
 * call never hangs on it (the official dialog has no bound; see `docs/notes-consent.md`).
 */
class LaunchApproval(
    private val consent: LaunchConsent,
    private val clock: Clock,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) {

    suspend fun approve(request: LaunchRequest): ToolResult<Unit> {
        if (!consent.isRequired()) return ToolResult.success(Unit)
        val name = request.configuration.name
        val verb = if (request.purpose == LaunchPurpose.START) "start" else "restart"
        return when (val answer = clock.withTimeout(timeout) { consent.ask(request) }) {
            is ConsentAnswer.Allowed -> {
                if (answer.stopAsking) consent.stopAsking()
                ToolResult.success(Unit)
            }

            ConsentAnswer.Denied -> ToolResult.failure(
                ErrorCode.DENIED,
                "The user denied the request to $verb '$name' when the IDE asked. Nothing was started or stopped.",
                "Do not retry on your own. Ask the user whether '$name' should run and why; retry only when they " +
                    "agree, then they allow it in the IDE.",
            )

            is ConsentAnswer.Unavailable -> ToolResult.failure(
                ErrorCode.UNSUPPORTED,
                "A $verb of '$name' needs the user's confirmation, but nobody can be asked: ${answer.reason}. " +
                    "Nothing was started or stopped.",
                "Ask the user to start it in the IDE, or to turn on brave mode in the MCP Server settings.",
            )

            null -> ToolResult.failure(
                ErrorCode.DENIED,
                "The user did not answer the IDE's question whether to $verb '$name' within " +
                    "${timeout.inWholeSeconds} s; the question was withdrawn. Nothing was started or stopped.",
                "Do not retry blindly. Tell the user that the IDE is asking, then retry once they are at the IDE.",
            )
        }
    }

    companion object {
        /** How long an unanswered question waits. */
        val DEFAULT_TIMEOUT: Duration = 2.minutes
    }
}
