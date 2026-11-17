package com.github.sinlordep.mcpcompanion.testing

import com.github.sinlordep.mcpcompanion.common.consent.ide.BraveModeSetting
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer

/**
 * Sets whether `mc_run` asks before launches (the MCP Server's brave mode, an application setting) until [disposable]
 * is disposed, then restores the earlier value. Platform tests start processes without a question; a headless test IDE
 * could not show one.
 */
fun launchConfirmation(asks: Boolean, disposable: Disposable) {
    val before = BraveModeSetting().asksBeforeLaunch
    BraveModeSetting().asksBeforeLaunch = asks
    Disposer.register(disposable) { BraveModeSetting().asksBeforeLaunch = before }
}
