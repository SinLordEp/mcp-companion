package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.common.time.timeoutOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** `start`: starts an existing run configuration and returns at once with its handle. */
class StartProcess(private val configurations: ConfigurationResolver, private val lifecycle: ProcessLifecycle) :
    RunActionHandler {

    override val action = RunAction.START
    override val parameters = setOf(RunParameter.CONFIGURATION, RunParameter.TYPE, RunParameter.EXECUTOR)

    override suspend fun handle(request: RunArguments): ToolResult<RunOutcome> {
        val executor = request.executor ?: ExecutorKind.RUN
        if (executor == ExecutorKind.OTHER) {
            return ToolResult.failure(ErrorCode.INVALID_ARGUMENT, "executor must be run or debug.", null)
        }
        return configurations.resolve(request.configuration, request.type)
            .flatMap { lifecycle.start(it, executor) }
            .map { RunOutcome.Process(it.snapshot) }
    }
}

/** `stop`: what the IDE's Stop does, waiting for the end; `force` kills a process that outlives the timeout. */
class StopProcess(private val processes: ProcessResolver, private val lifecycle: ProcessLifecycle) :
    RunActionHandler {

    override val action = RunAction.STOP
    override val parameters = STOP_PARAMETERS

    override suspend fun handle(request: RunArguments): ToolResult<RunOutcome> =
        stopTimeout(request).flatMap { timeout ->
            processes.resolve(request, aliveOnly = true).flatMap { process ->
                lifecycle.stop(process, timeout, request.force == true)
                    .map { RunOutcome.Stopped(process.snapshot, it) }
            }
        }
}

/** `restart`: stop (if alive), then start the same configuration with the same executor. */
class RestartProcess(private val processes: ProcessResolver, private val lifecycle: ProcessLifecycle) :
    RunActionHandler {

    override val action = RunAction.RESTART
    override val parameters = STOP_PARAMETERS

    override suspend fun handle(request: RunArguments): ToolResult<RunOutcome> =
        stopTimeout(request).flatMap { timeout ->
            processes.resolve(request, aliveOnly = true).flatMap {
                lifecycle.restart(it, timeout, request.force == true)
            }
        }
}

private val STOP_PARAMETERS = setOf(
    RunParameter.PROCESS,
    RunParameter.CONFIGURATION,
    RunParameter.TYPE,
    RunParameter.TIMEOUT,
    RunParameter.FORCE,
)

private val DEFAULT_STOP_TIMEOUT: Duration = 10.seconds
private val MAX_STOP_TIMEOUT: Duration = 120.seconds

private fun stopTimeout(request: RunArguments): ToolResult<Duration> =
    timeoutOf(request.timeoutSeconds, DEFAULT_STOP_TIMEOUT, MAX_STOP_TIMEOUT)
