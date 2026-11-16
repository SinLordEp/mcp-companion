package com.github.sinlordep.mcpcompanion.contract

import com.github.sinlordep.mcpcompanion.sync.SyncActivity
import com.github.sinlordep.mcpcompanion.sync.maven.MavenSyncProgress
import com.github.sinlordep.mcpcompanion.yield.reflectMcpToolNames
import com.intellij.mcpserver.McpToolSchema
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.ExternalSystemManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.project.MavenSyncListener

/**
 * What `mc_modules` and `mc_sync` rely on and what they leave to the bundled server (docs/notes-step4.md). If one of
 * these fails after an IDE update, re-check the step-4 decisions before changing the expectation.
 */
class BuildToolsContractTest : BasePlatformTestCase() {

    private fun served(name: String) = McpServerService.getInstance().getMcpTools().single {
        it.descriptor.name == name
    }

    /**
     * The official tools about modules, dependencies, building and project sync in this IDE. A new name here may
     * duplicate `mc_modules` or `mc_sync`: check it, and add a catalog entry if it replaces a whole tool.
     */
    fun testTheOfficialModuleDependencyBuildAndSyncToolsAreTheAnalysedOnes() {
        val related = Regex("module|depend|librar|build|compile|sync|reload|refresh|gradle|maven|import_project")
        val names = McpToolset.EP.extensionList
            .filterNot { it.javaClass.name.startsWith("com.github.sinlordep.mcpcompanion") }
            .flatMap { reflectMcpToolNames(it.javaClass) }
            .filter { related.containsMatchIn(it) }
            .toSortedSet()
        // compile_inspection_kts (Qodana) compiles an inspection script; it is unrelated to the project build.
        assertEquals(
            sortedSetOf("build_project", "compile_inspection_kts", "get_project_dependencies", "get_project_modules"),
            names,
        )
    }

    /**
     * Why `list`, `graph` and `cycles` are not duplicates: the official module list has name and type only (no
     * content roots, folders, build system or SDK), and the official dependency list is a flat list of library names
     * (no module edges, no scope, no exported flag, no owning module).
     */
    fun testTheOfficialModuleAndDependencyToolsReturnNoRootsEdgesOrScopes() {
        assertEquals(
            setOf("modules", "name", "type"),
            propertyNames(served("get_project_modules").descriptor.outputSchema),
        )
        assertEquals(
            setOf("dependencies", "name", "version", "dependencyType", "source"),
            propertyNames(served("get_project_dependencies").descriptor.outputSchema),
        )
        // The server adds projectPath to every tool; neither tool takes anything else (no module, no scope filter).
        assertEquals(setOf("projectPath"), served("get_project_modules").descriptor.inputSchema.propertiesSchema.keys)
        assertEquals(
            setOf("projectPath"),
            served("get_project_dependencies").descriptor.inputSchema.propertiesSchema.keys,
        )
    }

    /**
     * Why there is no `build` action: the official build tool builds the project or given files, waits and returns
     * problems with file and position (also for delegated Gradle/Maven builds). What it lacks is a module scope.
     */
    fun testTheOfficialBuildToolTakesTheseArgumentsAndReturnsProblemsWithPositions() {
        val tool = served("build_project").descriptor
        assertEquals(
            setOf("rebuild", "filesToRebuild", "timeout", "projectPath"),
            tool.inputSchema.propertiesSchema.keys,
        )
        assertTrue(
            propertyNames(
                tool.outputSchema,
            ).containsAll(setOf("isSuccess", "problems", "file", "line", "column", "message")),
        )
    }

    /** The external-system support mc_sync drives for Gradle is part of this IDE's platform. */
    fun testTheExternalSystemExtensionPointIsAvailable() {
        assertNotNull(ExternalSystemManager.EP_NAME.point)
    }

    /**
     * Maven's sync topic is an application topic: our application listener sees a project's sync start and end. The
     * Maven gateway counts them to learn when its sync ended.
     */
    fun testMavenSyncEventsReachOurApplicationListener() {
        project.replaceService(MavenSyncProgress::class.java, MavenSyncProgress(), testRootDisposable)
        val publisher = ApplicationManager.getApplication().messageBus.syncPublisher(MavenSyncListener.TOPIC)
        publisher.syncStarted(project)
        assertEquals(SyncActivity(busy = true, starts = 1), MavenSyncProgress.getInstance(project).activity.value)
        publisher.syncFinished(project)
        assertEquals(SyncActivity(busy = false, starts = 1), MavenSyncProgress.getInstance(project).activity.value)
        assertEquals(1L, MavenSyncProgress.getInstance(project).finished.value)
    }

    /**
     * `sync/maven` also uses the external-system API (`ExternalSystemProjectNotificationAware`) while its descriptor
     * depends only on the Maven plugin. That is safe because the Maven plugin's required content module
     * `intellij.maven` (loading="embedded") depends on `intellij.platform.externalSystem` and its `.impl`, so wherever
     * our Maven descriptor loads, the external-system classes are loadable too.
     */
    fun testTheMavenPluginRequiresTheExternalSystemModules() {
        val descriptor = MavenProjectsManager::class.java.classLoader.getResources("META-INF/plugin.xml").toList()
            .map { it.readText() }
            .single { "<id>org.jetbrains.idea.maven</id>" in it }
        val start = descriptor.indexOf("<module name=\"intellij.maven\" loading=\"embedded\">")
        assertTrue("the Maven plugin's required content module intellij.maven", start >= 0)
        val mavenModule = descriptor.substring(start, descriptor.indexOf("</dependencies>", start))
        assertTrue(mavenModule, "<module name=\"intellij.platform.externalSystem\" />" in mavenModule)
        assertTrue(mavenModule, "<module name=\"intellij.platform.externalSystem.impl\" />" in mavenModule)
    }

    /** The MCP hints we declare: mc_modules is read-only; mc_sync changes IDE state and may use the network. */
    fun testToolHints() {
        val modules = served("mc_modules").descriptor.annotations
        assertEquals(true, modules?.readOnlyHint)
        assertEquals(false, modules?.openWorldHint)
        val sync = served("mc_sync").descriptor.annotations
        assertEquals(false, sync?.readOnlyHint)
        assertEquals(false, sync?.destructiveHint)
        assertEquals("a sync runs build scripts again and may download", false, sync?.idempotentHint)
        assertEquals(true, sync?.openWorldHint)
    }

    /** Every property name in [schema], nested objects and definitions included. */
    private fun propertyNames(schema: McpToolSchema?): Set<String> {
        if (schema == null) return emptySet()
        val names = schema.propertiesSchema.keys.toMutableSet()
        (schema.propertiesSchema.values + schema.definitions.values).forEach { collect(it, names) }
        return names
    }

    private fun collect(element: JsonElement, names: MutableSet<String>) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                if (key == "properties" && value is JsonObject) names += value.keys
                collect(value, names)
            }

            is JsonArray -> element.forEach { collect(it, names) }

            else -> Unit
        }
    }
}
