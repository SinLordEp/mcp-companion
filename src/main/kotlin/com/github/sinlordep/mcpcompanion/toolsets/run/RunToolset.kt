package com.github.sinlordep.mcpcompanion.toolsets.run

import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.run.RunArguments
import com.github.sinlordep.mcpcompanion.run.ide.runControlFor
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.github.sinlordep.mcpcompanion.toolsets.toMcpOutput
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext

/** MCP adapter for `mc_run`. */
class RunToolset : CompanionToolset {

    override val toolsetId: String = ID

    @McpTool(name = "mc_run")
    @McpToolHints(
        readOnlyHint = McpToolHintValue.FALSE,
        destructiveHint = McpToolHintValue.TRUE,
        idempotentHint = McpToolHintValue.FALSE,
        openWorldHint = McpToolHintValue.TRUE,
    )
    @McpDescription(description = DESCRIPTION)
    @Suppress("LongParameterList") // One parameter per MCP argument.
    suspend fun run(
        @McpDescription(RunParameterTexts.ACTION) action: RunActionInput,
        @McpDescription(RunParameterTexts.PROCESS) process: String? = null,
        @McpDescription(RunParameterTexts.CONFIGURATION) configuration: String? = null,
        @McpDescription(RunParameterTexts.TYPE) type: String? = null,
        @McpDescription(RunParameterTexts.EXECUTOR) executor: ExecutorInput? = null,
        @McpDescription(RunParameterTexts.LINES) lines: Int? = null,
        @McpDescription(RunParameterTexts.SINCE) since: Long? = null,
        @McpDescription(RunParameterTexts.PATTERN) pattern: String? = null,
        @McpDescription(RunParameterTexts.FAIL_PATTERN) failPattern: String? = null,
        @McpDescription(RunParameterTexts.PORT) port: Int? = null,
        @McpDescription(RunParameterTexts.TIMEOUT) timeoutSeconds: Int? = null,
        @McpDescription(RunParameterTexts.FORCE) force: Boolean? = null,
        @McpDescription(RunParameterTexts.RESTART_ON_FAILURE) restartOnFailure: Boolean? = null,
    ): RunOutput {
        val project = currentCoroutineContext().project
        val arguments = RunArguments(
            process = process,
            configuration = configuration,
            type = type,
            executor = executor?.kind,
            lines = lines,
            since = since,
            pattern = pattern,
            failPattern = failPattern,
            port = port,
            timeoutSeconds = timeoutSeconds,
            force = force,
            restartOnFailure = restartOnFailure,
        )
        return runControlFor(project).execute(action.action, arguments).map(RunOutput::from).toMcpOutput()
    }

    companion object {
        const val ID: String = "run"

        private const val DESCRIPTION: String = "Controls existing run configurations of the project (never " +
            "creates or edits them): start, stop, restart, read console output, wait until ready, hot-swap code. " +
            "Every process, also one the user started in the IDE, has a handle (p1, p2, ...), valid until its run " +
            "tab is closed or reused. Actions: processes - retained processes with state (starting, running, " +
            "terminating, terminated + exitCode, not_started + reason). start - configuration (+type if the name is " +
            "ambiguous), executor run|debug; returns at once with the handle; CONFLICT while that configuration " +
            "runs unless it allows parallel runs. stop - process or configuration; like the IDE's Stop, waits for " +
            "the exit; force kills after the timeout. restart - stop, then start with the same executor; returns " +
            "the new handle. status - one process. output - captured console (ANSI removed, bounded): last lines, " +
            "or the lines after since (poll with nextCursor); pattern filters. wait_ready - until pattern " +
            "(default: common server start messages), failPattern, port, or the end of the process, at most " +
            "timeoutSeconds; tells which condition held and the line. hotswap - JVM debug sessions: builds, " +
            "reloads changed classes without restart; reports compile errors or why the JVM refused (e.g. a method " +
            "added); restartOnFailure restarts instead. Parameters that do not apply to the action are rejected. " +
            "Typical: start -> wait_ready -> output -> edit code -> hotswap -> stop. Breakpoints, stepping: " +
            "xdebug_* tools."
    }
}

/** Parameter descriptions of `mc_run`: prompts for the agent, so short. */
private object RunParameterTexts {
    const val ACTION = "processes, start, stop, restart, status, output, wait_ready or hotswap."
    const val PROCESS = "Process handle (p1, p2, ...) from processes or start."
    const val CONFIGURATION = "Run configuration name; outside start it selects its latest process."
    const val TYPE = "Configuration type id or name, when several types use the name."
    const val EXECUTOR = "start: run (default) or debug."
    const val LINES = "output: number of lines, 1-500, default 50."
    const val SINCE = "output, wait_ready: only lines after this cursor (nextCursor of an earlier output)."
    const val PATTERN = "output: keep matching lines. wait_ready: ready pattern. Java regex, found anywhere in a line."
    const val FAIL_PATTERN = "wait_ready: a matching line ends the wait as failed."
    const val PORT = "wait_ready: ready when 127.0.0.1:port accepts a TCP connection."
    const val TIMEOUT = "stop, restart: wait for the exit, default 10, max 120. wait_ready: default 60, max 300."
    const val FORCE = "stop, restart: kill if still alive after timeoutSeconds."
    const val RESTART_ON_FAILURE = "hotswap: restart instead when the change cannot be reloaded."
}
