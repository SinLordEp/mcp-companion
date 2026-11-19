package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map

/** `start_at`: runs (or debugs) the code at a location, like the IDE's Run gutter icon, and returns its handle. */
class StartAtCode(private val code: CodeRunGateway, private val lifecycle: ProcessLifecycle) : RunActionHandler {

    override val action = RunAction.START_AT
    override val parameters = setOf(
        RunParameter.PATH,
        RunParameter.LINE,
        RunParameter.COLUMN,
        RunParameter.QUALIFIED_NAME,
        RunParameter.TYPE,
        RunParameter.CONFIGURATION,
        RunParameter.EXECUTOR,
    )

    override suspend fun handle(request: RunArguments): ToolResult<RunOutcome> {
        val executor = request.executor ?: ExecutorKind.RUN
        if (executor == ExecutorKind.OTHER) {
            return ToolResult.failure(ErrorCode.INVALID_ARGUMENT, "executor must be run or debug.", null)
        }
        val location = when (
            val parsed = CodeLocation.of(
                request.path,
                request.line,
                request.column,
                request.qualifiedName,
            )
        ) {
            is ToolResult.Success -> parsed.value
            is ToolResult.Failure -> return parsed
        }
        val candidate = when (val found = code.configurationsAt(location)) {
            is ToolResult.Success -> choose(location, found.value, request.type, request.configuration)
            is ToolResult.Failure -> found
        }
        return when (candidate) {
            is ToolResult.Failure -> candidate
            is ToolResult.Success -> launch(location, candidate.value, executor)
        }
    }

    private suspend fun launch(
        location: CodeLocation,
        candidate: CodeConfiguration,
        executor: ExecutorKind,
    ): ToolResult<RunOutcome> {
        candidate.refusal?.let { reason ->
            return ToolResult.failure(
                ErrorCode.UNSUPPORTED,
                "'${candidate.configuration.name}' cannot be started without a question in the IDE: $reason. " +
                    "Nothing was started.",
                "Point at a more specific location, or run it from the IDE.",
            )
        }
        return code.register(candidate).flatMap { configuration ->
            val origin = LaunchOrigin(location.text, temporary = !candidate.existing)
            val started = lifecycle.start(configuration, executor, origin)
            // Nothing runs from a configuration made for this launch: do not leave it behind.
            if (started is ToolResult.Failure && !candidate.existing) code.unregister(configuration)
            started.map { RunOutcome.Process(it.snapshot) }
        }
    }

    private fun choose(
        location: CodeLocation,
        candidates: List<CodeConfiguration>,
        type: String?,
        name: String?,
    ): ToolResult<CodeConfiguration> {
        val matching = candidates.filter { candidate ->
            (type == null || candidate.configuration.hasType(type)) &&
                (name == null || candidate.configuration.name == name)
        }
        return when {
            candidates.isEmpty() -> ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "The IDE has nothing to run at ${location.text}: no test, main function or other entry point " +
                    "there (no Run gutter icon).",
                "Point at a test class or method, a main function, or a directory of tests; " +
                    "get_run_configurations(filePath) lists the run points of a file.",
            )

            matching.size == 1 -> ToolResult.success(matching.single())

            matching.isEmpty() -> ToolResult.failure(
                ErrorCode.NOT_FOUND,
                "None of the run configurations for ${location.text} matches" +
                    "${type?.let { " type '$it'" }.orEmpty()}${name?.let { " name '$it'" }.orEmpty()}. " +
                    "They are: ${describe(candidates)}.",
                "Pass type (and configuration) of one of these.",
            )

            else -> ToolResult.failure(
                ErrorCode.AMBIGUOUS,
                "${matching.size} run configurations fit ${location.text}: ${describe(matching)}.",
                "Pass type with one of these type ids (and configuration with the name when a type repeats); " +
                    "nothing was started.",
            )
        }
    }

    private fun describe(candidates: List<CodeConfiguration>): String = candidates.joinToString { candidate ->
        val configuration = candidate.configuration
        "'${configuration.name}' (type ${configuration.typeId}, ${configuration.typeName}" +
            "${if (candidate.existing) ", existing" else ""})"
    }
}
