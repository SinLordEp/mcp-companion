package com.github.sinlordep.mcpcompanion.localhistory

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.result.ToolResult
import com.github.sinlordep.mcpcompanion.common.result.flatMap
import com.github.sinlordep.mcpcompanion.common.result.map
import java.time.ZoneId

/** `label`: a named label in the project's Local History, to come back to with `revert label=<name>`. */
class PutLabel(private val gateway: LocalHistoryGateway) : LocalHistoryActionHandler {

    override val action = LocalHistoryAction.LABEL
    override val parameters = setOf(LocalHistoryParameter.NAME)

    override suspend fun handle(request: LocalHistoryArguments): ToolResult<LocalHistoryOutcome> {
        val name = request.name?.trim().orEmpty()
        if (name.isEmpty() || name.length > LocalHistoryControl.MAX_LABEL_LENGTH) {
            return ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "name is required, 1-${LocalHistoryControl.MAX_LABEL_LENGTH} characters.",
                "Name the point you may come back to, e.g. \"before rewriting OrderService\".",
            )
        }
        return gateway.putLabel(name).map { LocalHistoryOutcome.Labelled(it) }
    }
}

/** `labels`: the labels this tool put that the IDE still keeps, newest first. */
class ListLabels(private val gateway: LocalHistoryGateway) : LocalHistoryActionHandler {

    override val action = LocalHistoryAction.LABELS
    override val parameters = emptySet<LocalHistoryParameter>()

    override suspend fun handle(request: LocalHistoryArguments): ToolResult<LocalHistoryOutcome> =
        ToolResult.success(LocalHistoryOutcome.Labels(gateway.labels()))
}

/** `diff` (changes since the target's state) and `preview` (what `revert` would change). Changes nothing. */
class ShowChanges(
    private val gateway: LocalHistoryGateway,
    private val zone: ZoneId,
    private val direction: DiffDirection,
) : LocalHistoryActionHandler {

    override val action =
        if (direction == DiffDirection.SINCE_THEN) LocalHistoryAction.DIFF else LocalHistoryAction.PREVIEW
    override val parameters = TARGET_PARAMETERS + LocalHistoryParameter.MAX_LINES

    override suspend fun handle(request: LocalHistoryArguments): ToolResult<LocalHistoryOutcome> =
        maxLines(request).flatMap { maxLines ->
            target(gateway, request, zone).flatMap { (path, target) ->
                gateway.compare(path, target, direction, maxLines)
                    .map { LocalHistoryOutcome.Changes(target, direction, it) }
            }
        }

    private fun maxLines(request: LocalHistoryArguments): ToolResult<Int> {
        val maxLines = request.maxLines ?: LocalHistoryControl.DEFAULT_MAX_LINES
        return if (maxLines in 1..LocalHistoryControl.MAX_LINES) {
            ToolResult.success(maxLines)
        } else {
            ToolResult.failure(
                ErrorCode.INVALID_ARGUMENT,
                "maxLines must be between 1 and ${LocalHistoryControl.MAX_LINES}, was $maxLines.",
            )
        }
    }
}

/**
 * `revert`: a file gets the content it had at the target (one undoable command; what `preview` shows); a directory is
 * reverted to a label with the IDE's own revert, which also deletes files created since and restores deleted ones. A
 * directory cannot be reverted to a time: the public API reverts directories to labels only.
 */
class RevertToTarget(private val gateway: LocalHistoryGateway, private val zone: ZoneId) : LocalHistoryActionHandler {

    override val action = LocalHistoryAction.REVERT
    override val parameters = TARGET_PARAMETERS

    override suspend fun handle(request: LocalHistoryArguments): ToolResult<LocalHistoryOutcome> =
        target(gateway, request, zone).flatMap { (path, target) ->
            gateway.isDirectory(path).flatMap { directory ->
                when {
                    !directory -> gateway.restoreFile(path, target, describe(target))

                    target is HistoryTarget.AtLabel -> gateway.revertToLabel(path, target.label)

                    else -> ToolResult.failure(
                        ErrorCode.UNSUPPORTED,
                        "$path is a directory: directories are reverted to a label only, not to a time.",
                        "Revert its files one by one with time, or put a label before the next change.",
                    )
                }.map { LocalHistoryOutcome.Reverted(path, target, it) }
            }
        }

    private fun describe(target: HistoryTarget): String = when (target) {
        is HistoryTarget.AtLabel -> "'${target.label.name}'"

        is HistoryTarget.AtTime -> java.time.Instant.ofEpochMilli(target.epochMillis).atZone(zone)
            .toLocalDateTime().withNano(0).toString()
    }
}

private val TARGET_PARAMETERS =
    setOf(LocalHistoryParameter.PATH, LocalHistoryParameter.LABEL, LocalHistoryParameter.TIME)

/** The path (required) and the past state the arguments name. */
private suspend fun target(
    gateway: LocalHistoryGateway,
    request: LocalHistoryArguments,
    zone: ZoneId,
): ToolResult<Pair<String, HistoryTarget>> {
    val path = request.path?.takeIf { it.isNotBlank() } ?: return ToolResult.failure(
        ErrorCode.INVALID_ARGUMENT,
        "path is required: the file or directory to compare or restore.",
        "Pass a path relative to the project root (\".\" for the whole project) or an absolute path.",
    )
    val labels = if (request.label != null) gateway.labels() else emptyList()
    return HistoryTarget.of(request, labels, zone).map { path to it }
}
