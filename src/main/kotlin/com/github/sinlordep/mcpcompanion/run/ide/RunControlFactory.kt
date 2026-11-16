package com.github.sinlordep.mcpcompanion.run.ide

import com.github.sinlordep.mcpcompanion.run.PortProbe
import com.github.sinlordep.mcpcompanion.run.RunControl
import com.github.sinlordep.mcpcompanion.run.SystemClock
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Wires [RunControl] to the platform for [project]. Cheap; called once per tool call. */
fun runControlFor(project: Project): RunControl {
    val tracker = ProcessTracker.getInstance(project)
    val supports = { HotSwapSupport.EP.extensionList }
    return RunControl.create(
        catalog = tracker,
        execution = IdeExecutionGateway(project, tracker, LaunchChecks(project, supports)),
        hotSwap = ExtensionHotSwapGateway(project, tracker, supports),
        ports = LoopbackPortProbe,
        clock = SystemClock,
        startLock = tracker.startLock,
    )
}

/** Connects to the loopback interface; a refused or timed-out connection means "not open yet". */
internal object LoopbackPortProbe : PortProbe {
    private const val CONNECT_TIMEOUT_MILLIS = 200

    override suspend fun isOpen(port: Int): Boolean = runInterruptible(Dispatchers.IO) {
        try {
            Socket().use {
                it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MILLIS)
            }
            true
        } catch (_: IOException) {
            false
        }
    }
}
