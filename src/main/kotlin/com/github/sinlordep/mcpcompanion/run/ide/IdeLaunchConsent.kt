package com.github.sinlordep.mcpcompanion.run.ide

import com.github.sinlordep.mcpcompanion.common.consent.ConfirmationSetting
import com.github.sinlordep.mcpcompanion.run.ConsentAnswer
import com.github.sinlordep.mcpcompanion.run.LaunchConsent
import com.github.sinlordep.mcpcompanion.run.LaunchRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.AppIcon
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Frame

/**
 * Asks the user in a modal dialog ([LaunchConsentDialog]) on the frame of [project]. The dialog lives exactly as long
 * as the question: when the caller is cancelled (the MCP client cancelled the call, or the question timed out) the
 * dialog is closed and the answer is dropped. A minimised frame is restored and the IDE requests attention when it is
 * not the active application, so that the question can be seen. Never asks in a headless IDE.
 */
internal class IdeLaunchConsent(private val project: Project, private val setting: ConfirmationSetting) :
    LaunchConsent {

    override fun isRequired(): Boolean = setting.asksBeforeLaunch

    override fun stopAsking() {
        setting.asksBeforeLaunch = false
    }

    override suspend fun ask(request: LaunchRequest): ConsentAnswer {
        if (ApplicationManager.getApplication().isHeadlessEnvironment) return ConsentAnswer.Unavailable(HEADLESS)
        val executorName = ProcessDescriptions.executorOf(request.executor)?.actionName ?: request.executor.name
        return coroutineScope {
            val dialog = withContext(Dispatchers.EDT) {
                LaunchConsentDialog(request, executorName, project, visibleFrame())
            }
            // Closes the dialog when the question is withdrawn; the modal loop runs events of any modality.
            val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable + Dispatchers.EDT + ModalityState.any().asContextElement()) {
                        if (!dialog.isDisposed) dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
                    }
                }
            }
            try {
                val allowed = withContext(Dispatchers.EDT) { dialog.showAndGet() }
                if (allowed) ConsentAnswer.Allowed(dialog.stopsAsking) else ConsentAnswer.Denied
            } finally {
                closer.cancel()
            }
        }
    }

    /** The project's frame, restored when minimised; the IDE asks for attention when another application is active. */
    private fun visibleFrame(): Frame? {
        val frame = WindowManager.getInstance().getFrame(project)
        if (frame != null && (frame.extendedState and Frame.ICONIFIED) != 0) {
            frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
        }
        if (!ApplicationManager.getApplication().isActive) AppIcon.getInstance().requestAttention(project, true)
        return frame?.takeIf { it.isShowing }
    }

    private companion object {
        const val HEADLESS = "the IDE runs without a user interface (headless)"
    }
}
