package com.github.sinlordep.mcpcompanion.toolsets.sync

import com.github.sinlordep.mcpcompanion.common.result.map
import com.github.sinlordep.mcpcompanion.sync.SyncArguments
import com.github.sinlordep.mcpcompanion.sync.ide.syncControlFor
import com.github.sinlordep.mcpcompanion.toolsets.CompanionToolset
import com.github.sinlordep.mcpcompanion.toolsets.toMcpOutput
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext

/** MCP adapter for `mc_sync`. */
class SyncToolset : CompanionToolset {

    override val toolsetId: String = ID

    @McpTool(name = "mc_sync")
    @McpToolHints(
        readOnlyHint = McpToolHintValue.FALSE,
        destructiveHint = McpToolHintValue.FALSE,
        // A sync runs the build scripts again and may download; repeating it is not free of further effects.
        idempotentHint = McpToolHintValue.FALSE,
        openWorldHint = McpToolHintValue.TRUE,
    )
    @McpDescription(description = DESCRIPTION)
    suspend fun sync(
        @McpDescription(SyncParameterTexts.ACTION) action: SyncActionInput,
        @McpDescription(SyncParameterTexts.BUILD_SYSTEM) buildSystem: String? = null,
        @McpDescription(SyncParameterTexts.PROJECT) project: String? = null,
        @McpDescription(SyncParameterTexts.TIMEOUT) timeoutSeconds: Int? = null,
        @McpDescription(SyncParameterTexts.WAIT_FOR_INDEXING) waitForIndexing: Boolean? = null,
    ): SyncOutput {
        val ideProject = currentCoroutineContext().project
        val arguments = SyncArguments(buildSystem, project, timeoutSeconds, waitForIndexing)
        return syncControlFor(ideProject).execute(action.action, arguments).map(SyncOutput::from).toMcpOutput()
    }

    companion object {
        const val ID: String = "sync"

        private const val DESCRIPTION: String = "Re-imports Gradle and Maven projects after their build files " +
            "changed (pom.xml, build.gradle(.kts), settings.gradle, ...), like the IDE's Reload, and waits for the " +
            "result; never opens a dialog. Actions: status - read-only: build systems this IDE can sync, their " +
            "linked projects (paths), needsReload (the IDE's 'Load changes' notice: per build system, lags an edit " +
            "by seconds; after editing a build file call sync, do not wait for it), whether a sync runs now, and " +
            "the latest sync this tool started. sync - re-imports all linked projects, or those of buildSystem " +
            "(gradle, maven) or the one at project (its path from status); waits up to timeoutSeconds (default " +
            "60, max 600) and reports per project outcome succeeded|failed|cancelled with the build tool's message " +
            "and the duration (failed: fix the build file and sync again; until a sync succeeds the project may miss " +
            "modules); state=running means the timeout passed and the sync goes on (call status later). " +
            "Maven syncs all Maven projects together. A sync never runs twice at once: a call waits for a running " +
            "sync, then starts its own. waitForIndexing also waits until the IDE is done configuring and " +
            "indexing. The build tool may download dependencies. Refused in an untrusted project. Then: " +
            "mc_modules to see the result."
    }
}

/** Parameter descriptions of `mc_sync`: prompts for the agent, so short. */
private object SyncParameterTexts {
    const val ACTION = "status or sync."
    const val BUILD_SYSTEM = "Only this build system: gradle, maven (ids from status)."
    const val PROJECT = "Only the linked project at this path (relative to the project directory, or absolute)."
    const val TIMEOUT = "sync: seconds to wait, default 60, max 600."
    const val WAIT_FOR_INDEXING = "sync: also wait until configuration and indexing are done (default false)."
}
