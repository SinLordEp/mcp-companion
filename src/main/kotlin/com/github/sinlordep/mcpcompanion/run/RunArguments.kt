package com.github.sinlordep.mcpcompanion.run

import com.github.sinlordep.mcpcompanion.common.actions.ToolArguments
import com.github.sinlordep.mcpcompanion.run.tests.TestFilter

/** The actions of `mc_run`. */
enum class RunAction { PROCESSES, START, STOP, RESTART, STATUS, OUTPUT, WAIT_READY, HOTSWAP, TESTS }

/** The optional arguments of `mc_run`; each action accepts a subset (see the handlers' `parameters`). */
enum class RunParameter(val label: String) {
    PROCESS("process"),
    CONFIGURATION("configuration"),
    TYPE("type"),
    EXECUTOR("executor"),
    LINES("lines"),
    SINCE("since"),
    PATTERN("pattern"),
    FAIL_PATTERN("failPattern"),
    PORT("port"),
    TIMEOUT("timeoutSeconds"),
    FORCE("force"),
    RESTART_ON_FAILURE("restartOnFailure"),
    FILTER("filter"),
    LIMIT("limit"),
    OFFSET("offset"),
    STACK_LINES("stackLines"),
}

/** Raw client arguments of one `mc_run` call, not yet validated. Null means "not given". */
data class RunArguments(
    val process: String? = null,
    val configuration: String? = null,
    val type: String? = null,
    val executor: ExecutorKind? = null,
    val lines: Int? = null,
    val since: Long? = null,
    val pattern: String? = null,
    val failPattern: String? = null,
    val port: Int? = null,
    val timeoutSeconds: Int? = null,
    val force: Boolean? = null,
    val restartOnFailure: Boolean? = null,
    val filter: TestFilter? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val stackLines: Int? = null,
) : ToolArguments<RunParameter> {
    /** The parameters the client gave. */
    override fun given(): Set<RunParameter> = buildSet {
        val values = mapOf(
            RunParameter.PROCESS to process,
            RunParameter.CONFIGURATION to configuration,
            RunParameter.TYPE to type,
            RunParameter.EXECUTOR to executor,
            RunParameter.LINES to lines,
            RunParameter.SINCE to since,
            RunParameter.PATTERN to pattern,
            RunParameter.FAIL_PATTERN to failPattern,
            RunParameter.PORT to port,
            RunParameter.TIMEOUT to timeoutSeconds,
            RunParameter.FORCE to force,
            RunParameter.RESTART_ON_FAILURE to restartOnFailure,
            RunParameter.FILTER to filter,
            RunParameter.LIMIT to limit,
            RunParameter.OFFSET to offset,
            RunParameter.STACK_LINES to stackLines,
        )
        values.forEach { (parameter, value) -> if (value != null) add(parameter) }
    }
}
