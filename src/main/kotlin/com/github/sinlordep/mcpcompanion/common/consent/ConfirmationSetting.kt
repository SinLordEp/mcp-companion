package com.github.sinlordep.mcpcompanion.common.consent

/**
 * The IDE-wide choice whether MCP clients must be confirmed by the user before they start processes. It is the bundled
 * MCP Server's own switch ("brave mode"), so one setting governs the official tools and ours; see
 * `docs/notes-consent.md`.
 */
interface ConfirmationSetting {
    /** True (the default) when the user is asked before an MCP client starts a run configuration. */
    var asksBeforeLaunch: Boolean
}
