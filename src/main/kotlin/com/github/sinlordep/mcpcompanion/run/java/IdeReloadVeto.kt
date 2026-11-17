package com.github.sinlordep.mcpcompanion.run.java

import com.intellij.debugger.ui.HotSwapUI
import com.intellij.debugger.ui.HotSwapVetoableListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.task.ProjectTaskContext

/**
 * Keeps the IDE from offering to reload classes after builds this plugin starts. The IDE's `HotSwapUIImpl` listens to
 * every `ProjectTaskManager` build; when one succeeds while debug sessions run it reloads the changed classes, by
 * default after a modal question (`RunHotswapDialog`). A vetoable listener (public `HotSwapUI` API) declines that for
 * the builds [declines] picks, as long as [lifetime] lasts. Settings are never changed.
 */
internal object IdeReloadVeto {
    fun install(project: Project, lifetime: Disposable, declines: (ProjectTaskContext) -> Boolean) {
        val hotSwap = HotSwapUI.getInstance(project)
        val veto = HotSwapVetoableListener { context -> !declines(context) }
        hotSwap.addListener(veto)
        Disposer.register(lifetime) { hotSwap.removeListener(veto) }
    }
}
