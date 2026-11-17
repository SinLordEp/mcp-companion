package com.github.sinlordep.mcpcompanion.common.consent.ide

import com.github.sinlordep.mcpcompanion.common.consent.ConfirmationSetting
import com.intellij.mcpserver.settings.McpServerSettings

/**
 * [ConfirmationSetting] stored as the bundled MCP Server's brave mode (`McpServerSettings.enableBraveMode`, public API
 * of the MCP Server plugin, application level, off by default). The official `execute_run_configuration` and
 * `execute_terminal_command` ask unless it is on, and their confirmation dialog turns it on; this is the same switch.
 * Stateless: read on every call, so a change on either settings page takes effect at once.
 */
class BraveModeSetting : ConfirmationSetting {
    override var asksBeforeLaunch: Boolean
        get() = !McpServerSettings.getInstance().enableBraveMode
        set(value) {
            McpServerSettings.getInstance().enableBraveMode = !value
        }
}
