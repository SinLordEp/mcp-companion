package com.github.sinlordep.mcpcompanion.run.ide

import com.github.sinlordep.mcpcompanion.run.ExecutorKind
import com.github.sinlordep.mcpcompanion.run.LaunchOrigin
import com.github.sinlordep.mcpcompanion.run.LaunchPurpose
import com.github.sinlordep.mcpcompanion.run.LaunchRequest
import com.github.sinlordep.mcpcompanion.run.configuration
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import javax.swing.JLabel

/** The texts of the launch question; the dialog itself needs a display and is checked end to end. */
class LaunchConsentDialogTest : BasePlatformTestCase() {

    private fun labels(content: LaunchConsentPanel): List<String> =
        UIUtil.findComponentsOfType(content.panel, JLabel::class.java).map { it.text }

    fun testAStartNamesConfigurationTypeAndExecutor() {
        val server = configuration("Server", typeName = "Application")
        val content = LaunchConsentPanel(LaunchRequest(server, ExecutorKind.DEBUG, LaunchPurpose.START), "Debug")
        val labels = labels(content)
        assertTrue(labels.toString(), "MCP client wants to start run configuration 'Server'" in labels)
        assertTrue(labels.toString(), "Application" in labels && "Debug" in labels)
        assertTrue(labels.toString(), "Type:" in labels && "Executor:" in labels)
        assertFalse(labels.toString(), labels.any { it.contains("stopped first") || it == "Code:" })
        assertEquals(
            "Run shell commands or run configurations without confirmation (brave mode)",
            content.withoutConfirmation.text,
        )
        assertFalse("never pre-ticked", content.withoutConfirmation.isSelected)
    }

    fun testARestartSaysThatTheRunningProcessStops() {
        val request =
            LaunchRequest(configuration("Server"), ExecutorKind.RUN, LaunchPurpose.RESTART, stopsRunning = true)
        val labels = labels(LaunchConsentPanel(request, "Run"))
        assertTrue(labels.toString(), "MCP client wants to restart run configuration 'Server'" in labels)
        assertTrue(labels.toString(), "Its running process is stopped first." in labels)
    }

    fun testAHotSwapRestartSaysWhy() {
        val request = LaunchRequest(configuration("Server"), ExecutorKind.DEBUG, LaunchPurpose.HOTSWAP_RESTART)
        val labels = labels(LaunchConsentPanel(request, "Debug"))
        assertTrue(
            labels.toString(),
            "MCP client wants to restart run configuration 'Server' because the changed code cannot be reloaded" in
                labels,
        )
    }

    fun testAStartFromCodeNamesTheLocationAndTheTemporaryConfiguration() {
        val test = configuration("CalcTest.adds", typeName = "JUnit")
        val origin = LaunchOrigin("src/app/CalcTest.java:12", temporary = true)
        val labels =
            labels(
                LaunchConsentPanel(LaunchRequest(test, ExecutorKind.RUN, LaunchPurpose.START, origin = origin), "Run"),
            )
        assertTrue(labels.toString(), "MCP client wants to start run configuration 'CalcTest.adds'" in labels)
        assertTrue(labels.toString(), "Code:" in labels && "src/app/CalcTest.java:12" in labels)
        assertTrue(labels.toString(), "A temporary run configuration is created for it." in labels)

        val existing = LaunchOrigin("app.CalcTest", temporary = false)
        val again =
            labels(
                LaunchConsentPanel(
                    LaunchRequest(test, ExecutorKind.RUN, LaunchPurpose.START, origin = existing),
                    "Run",
                ),
            )
        assertTrue(again.toString(), "app.CalcTest" in again)
        assertFalse(again.toString(), again.any { it.startsWith("A temporary") })
    }
}
