package com.github.sinlordep.mcpcompanion.run

/** Why a process is launched; the question shown to the user names it. */
enum class LaunchPurpose {
    START,

    /** `restart`: a running process of the configuration is stopped first (when [LaunchRequest.stopsRunning]). */
    RESTART,

    /** `hotswap` with `restartOnFailure`: the changed code could not be reloaded, so the process restarts. */
    HOTSWAP_RESTART,
}

/** One launch an MCP client asks for: what the user is asked to allow. */
data class LaunchRequest(
    val configuration: ConfigurationRef,
    val executor: ExecutorKind,
    val purpose: LaunchPurpose,
    /** True when a running process of the configuration is stopped before the launch. */
    val stopsRunning: Boolean = false,
)

/** The user's answer to a [LaunchRequest]. */
sealed interface ConsentAnswer {
    /** Allowed; [stopAsking] when the user also chose to launch without confirmation from now on. */
    data class Allowed(val stopAsking: Boolean) : ConsentAnswer

    data object Denied : ConsentAnswer

    /** Nobody can be asked, e.g. the IDE runs without a user interface. */
    data class Unavailable(val reason: String) : ConsentAnswer
}

/** Gateway boundary: the user's confirmation of launches requested by MCP clients. */
interface LaunchConsent {
    /** False when the user chose to let MCP clients launch without confirmation. */
    fun isRequired(): Boolean

    /**
     * Asks the user and suspends until they answer. Cancelling the caller withdraws the question (the dialog closes)
     * and nothing is decided.
     */
    suspend fun ask(request: LaunchRequest): ConsentAnswer

    /** Records the user's choice to launch without confirmation from now on. */
    fun stopAsking()
}
