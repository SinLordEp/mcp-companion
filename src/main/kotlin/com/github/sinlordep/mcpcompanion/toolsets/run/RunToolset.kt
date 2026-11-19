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
        @McpDescription(RunParameterTexts.FILTER) filter: TestFilterInput? = null,
        @McpDescription(RunParameterTexts.LIMIT) limit: Int? = null,
        @McpDescription(RunParameterTexts.OFFSET) offset: Int? = null,
        @McpDescription(RunParameterTexts.STACK_LINES) stackLines: Int? = null,
        @McpDescription(RunParameterTexts.PATH) path: String? = null,
        @McpDescription(RunParameterTexts.LINE) line: Int? = null,
        @McpDescription(RunParameterTexts.COLUMN) column: Int? = null,
        @McpDescription(RunParameterTexts.QUALIFIED_NAME) qualifiedName: String? = null,
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
            filter = filter?.filter,
            limit = limit,
            offset = offset,
            stackLines = stackLines,
            path = path,
            line = line,
            column = column,
            qualifiedName = qualifiedName,
        )
        return runControlFor(project).execute(action.action, arguments).map(RunOutput::from).toMcpOutput()
    }

    companion object {
        const val ID: String = "run"

        private const val DESCRIPTION: String = "Runs code and run configurations of the project and controls " +
            "the processes: start (an existing configuration; never creates or edits saved ones) or start_at (code " +
            "directly, like the Run gutter icon), stop, restart, read console output, wait until ready, hot-swap " +
            "code, read test results. " +
            "Every process, also one the user started in the IDE, has a handle (p1, p2, ...), valid until its run " +
            "tab is closed or reused. Actions: processes - retained processes with state (starting, running, " +
            "terminating, terminated + exitCode, not_started + reason; test runs add a tests summary). start - " +
            "configuration (+type if the name is ambiguous), executor run|debug; returns at once with the handle; " +
            "CONFLICT while that configuration runs unless it allows parallel runs. start_at - what the IDE runs at " +
            "a location, no configuration needed: path of a file (+line, optional column: the test method, test " +
            "class or main function there) or of a directory (its tests), or qualifiedName (Class or " +
            "Class#method); executor run|debug. The IDE's own producers decide (JUnit, TestNG, Gradle when tests " +
            "run with Gradle, Application, Kotlin); a matching existing configuration is reused, otherwise a " +
            "temporary one is made (the IDE's rules: not shared, the oldest goes; temporary=true). Several " +
            "candidates: AMBIGUOUS lists them, pass type (and configuration with the name) to pick; nothing to run " +
            "there: NOT_FOUND; tests of an abstract class (the IDE would ask which subclass): UNSUPPORTED. Then like " +
            "start. stop - process or configuration; like the IDE's Stop, waits for the exit; force kills after " +
            "the timeout. restart - stop, then start with the same executor; returns the new handle. status - one " +
            "process. output - captured console (ANSI removed, bounded): last lines, or the lines after since (poll " +
            "with nextCursor); pattern filters. wait_ready - until pattern (default: common server start " +
            "messages), failPattern, port, or the end of the process, at most timeoutSeconds; tells which " +
            "condition held and the line. hotswap - JVM debug sessions: builds, reloads changed classes without " +
            "restart; reports compile errors or why the JVM refused (e.g. a method added); restartOnFailure " +
            "restarts instead. tests - results of a test run (JUnit, TestNG, Gradle test tasks and other IDE test " +
            "consoles), also while it runs (partial=true): verdict (running, passed, failed, no_tests, " +
            "interrupted), counts, then one page of tests by filter (default failed) with suite, path+line, " +
            "message, expected/actual, stack trace (stackLines); timeoutSeconds waits for the end. Without " +
            "results: verdict not_started or no_results + reason (e.g. Maven runs: read output). " +
            "Consent: start, start_at, restart and a hotswap that restarts first ask the user in the IDE " +
            "(Allow/Deny), as the bundled execute_run_configuration does, unless the user turned on brave mode; one " +
            "question per call, after the checks that need no user. Refused or unanswered within 120 s: DENIED, " +
            "nothing started or stopped - do not retry, ask the user. " +
            "Parameters that do not apply to the action are rejected. Typical: start -> wait_ready -> output -> " +
            "edit code -> hotswap -> stop; after a code change: start_at (the test class or method) -> tests " +
            "timeoutSeconds=120. Breakpoints, stepping: xdebug_* tools."
    }
}

/** Parameter descriptions of `mc_run`: prompts for the agent, so short. */
private object RunParameterTexts {
    const val ACTION = "processes, start, start_at, stop, restart, status, output, wait_ready, hotswap or tests."
    const val PROCESS = "Process handle (p1, p2, ...) from processes or start."
    const val CONFIGURATION = "Run configuration name; outside start and start_at it selects its latest process. " +
        "start_at: the name of one of several candidates."
    const val TYPE = "Configuration type id or name, when several types use the name (start_at: several candidates)."
    const val EXECUTOR = "start, start_at: run (default) or debug."
    const val LINES = "output: number of lines, 1-500, default 50."
    const val SINCE = "output, wait_ready: only lines after this cursor (nextCursor of an earlier output)."
    const val PATTERN = "output: keep matching lines. wait_ready: ready pattern. Java regex, found anywhere in a line."
    const val FAIL_PATTERN = "wait_ready: a matching line ends the wait as failed."
    const val PORT = "wait_ready: ready when 127.0.0.1:port accepts a TCP connection."
    const val TIMEOUT = "stop, restart: wait for the exit, default 10, max 120. wait_ready: default 60, max 300. " +
        "tests: wait for the end of the test run, default 0 (answer at once), max 300."
    const val FORCE = "stop, restart: kill if still alive after timeoutSeconds."
    const val RESTART_ON_FAILURE = "hotswap: restart instead when the change cannot be reloaded (asks the user first)."
    const val FILTER = "tests: which tests to list: failed (default; assertions or exceptions), passed, ignored, all."
    const val LIMIT = "tests: page size, 1-1000, default 20."
    const val OFFSET = "tests: first test of the page, default 0; continue with nextOffset."
    const val STACK_LINES = "tests: lines kept of each stack trace, 0-200, default 10."
    const val PATH = "start_at: file or directory, relative to the project root or absolute."
    const val LINE = "start_at: 1-based line in path: the test method, test class or main function there."
    const val COLUMN = "start_at: 1-based column on line; default its first non-blank character."
    const val QUALIFIED_NAME = "start_at: instead of path: a class (com.example.FooTest) or member (FooTest#adds)."
}
