package com.github.sinlordep.mcpcompanion.toolsets.run

import com.github.sinlordep.mcpcompanion.run.ExecutorKind
import com.github.sinlordep.mcpcompanion.run.RunAction
import com.github.sinlordep.mcpcompanion.run.tests.TestFilter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** MCP input values of `action`. */
@Serializable
enum class RunActionInput(val action: RunAction) {
    @SerialName("processes")
    PROCESSES(RunAction.PROCESSES),

    @SerialName("start")
    START(RunAction.START),

    @SerialName("start_at")
    START_AT(RunAction.START_AT),

    @SerialName("stop")
    STOP(RunAction.STOP),

    @SerialName("restart")
    RESTART(RunAction.RESTART),

    @SerialName("status")
    STATUS(RunAction.STATUS),

    @SerialName("output")
    OUTPUT(RunAction.OUTPUT),

    @SerialName("wait_ready")
    WAIT_READY(RunAction.WAIT_READY),

    @SerialName("hotswap")
    HOTSWAP(RunAction.HOTSWAP),

    @SerialName("tests")
    TESTS(RunAction.TESTS),
}

/** MCP input values of `filter` (action `tests`). */
@Serializable
enum class TestFilterInput(val filter: TestFilter) {
    @SerialName("failed")
    FAILED(TestFilter.FAILED),

    @SerialName("passed")
    PASSED(TestFilter.PASSED),

    @SerialName("ignored")
    IGNORED(TestFilter.IGNORED),

    @SerialName("all")
    ALL(TestFilter.ALL),
}

/** MCP input values of `executor`. */
@Serializable
enum class ExecutorInput(val kind: ExecutorKind) {
    @SerialName("run")
    RUN(ExecutorKind.RUN),

    @SerialName("debug")
    DEBUG(ExecutorKind.DEBUG),
}
