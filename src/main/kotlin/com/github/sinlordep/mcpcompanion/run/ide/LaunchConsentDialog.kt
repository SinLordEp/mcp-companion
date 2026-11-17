package com.github.sinlordep.mcpcompanion.run.ide

import com.github.sinlordep.mcpcompanion.McpCompanionBundle
import com.github.sinlordep.mcpcompanion.run.LaunchPurpose
import com.github.sinlordep.mcpcompanion.run.LaunchRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import java.awt.Component
import javax.swing.JComponent

/**
 * The question shown before an MCP client launches a run configuration, laid out like the bundled MCP Server's own
 * confirmation ("MCP client wants to execute run configuration …"): the question, what is launched, the brave-mode
 * checkbox with a note, and Allow (default button) / Deny. Modal to the whole IDE like the official one, owned by
 * [parent] (the project's frame) when given. The checkbox is read by the caller, and only after Allow.
 */
internal class LaunchConsentDialog(
    request: LaunchRequest,
    executorName: String,
    project: Project,
    parent: Component?,
) : DialogWrapper(project, parent, true, IdeModalityType.IDE) {

    private val content = LaunchConsentPanel(request, executorName)

    /** True when the user ticked "without confirmation"; meaningful after Allow. */
    val stopsAsking: Boolean get() = content.withoutConfirmation.isSelected

    init {
        title = McpCompanionBundle.message("consent.title")
        setOKButtonText(McpCompanionBundle.message("consent.allow"))
        setCancelButtonText(McpCompanionBundle.message("consent.deny"))
        init()
    }

    override fun createCenterPanel(): JComponent = content.panel
}

/** The body of [LaunchConsentDialog]: the question, type and executor, and the "without confirmation" checkbox. */
internal class LaunchConsentPanel(request: LaunchRequest, executorName: String) {

    lateinit var withoutConfirmation: JBCheckBox
        private set

    val panel: DialogPanel = panel {
        val name = request.configuration.name
        val question = when (request.purpose) {
            LaunchPurpose.START -> McpCompanionBundle.message("consent.question.start", name)
            LaunchPurpose.RESTART -> McpCompanionBundle.message("consent.question.restart", name)
            LaunchPurpose.HOTSWAP_RESTART -> McpCompanionBundle.message("consent.question.hotswap", name)
        }
        row { label(question) }
        indent {
            row(McpCompanionBundle.message("consent.type")) { label(request.configuration.typeName) }
            row(McpCompanionBundle.message("consent.executor")) { label(executorName) }
            if (request.stopsRunning) row { label(McpCompanionBundle.message("consent.stops.running")) }
        }
        row {
            withoutConfirmation = checkBox(McpCompanionBundle.message("consent.without.confirmation"))
                .comment(McpCompanionBundle.message("consent.without.confirmation.note"))
                .component
        }
    }
}
