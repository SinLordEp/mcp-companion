package com.github.sinlordep.mcpcompanion.toolsets.run

import com.github.sinlordep.mcpcompanion.run.ExecutorKind
import com.github.sinlordep.mcpcompanion.run.RunAction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** MCP input values of `action`. */
@Serializable
enum class RunActionInput(val action: RunAction) {
    @SerialName("processes")
    PROCESSES(RunAction.PROCESSES),

    @SerialName("start")
    START(RunAction.START),

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
}

/** MCP input values of `executor`. */
@Serializable
enum class ExecutorInput(val kind: ExecutorKind) {
    @SerialName("run")
    RUN(ExecutorKind.RUN),

    @SerialName("debug")
    DEBUG(ExecutorKind.DEBUG),
}
