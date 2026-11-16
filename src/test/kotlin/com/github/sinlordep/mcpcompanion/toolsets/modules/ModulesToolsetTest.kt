package com.github.sinlordep.mcpcompanion.toolsets.modules

import com.github.sinlordep.mcpcompanion.testing.LibraryJars
import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.util.projectDirectory
import com.intellij.mcpserver.util.relativizeIfPossible
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.java.JpsJavaExtensionService
import org.junit.Assert
import java.io.File

/**
 * `mc_modules` on real modules: content roots inside and next to the project directory, source, test, resource and
 * excluded folders, scoped and exported dependencies, a library, a cycle and a test-only cycle.
 *
 * Modules: `app` (root `app/`) depends on `core` (compile, exported) and on `util` (test) and uses library `lib`;
 * `core` (root next to the project directory) and `util` depend on each other (runtime, provided); `testkit` depends
 * on `app` and `app` on `testkit` in test scope only.
 */
class ModulesToolsetTest : HeavyPlatformTestCase() {

    private lateinit var tools: ToolCalls
    private lateinit var coreRoot: VirtualFile
    private val outside = mutableListOf<File>()
    private val names = setOf("app", "core", "util", "testkit")

    private val toolset get() = McpToolset.EP.extensionList.filterIsInstance<ModulesToolset>().single()

    override fun setUp() {
        super.setUp()
        val base = getOrCreateProjectBaseDir()
        // The project directory, as in a real project: the module named like the project has it as content root.
        PsiTestUtil.addContentRoot(module, base)
        val app = javaModule("app", directory(base.path, "app"))
        val appRoot = folder(base.path, "app")
        PsiTestUtil.addContentRoot(app, appRoot)
        PsiTestUtil.addSourceRoot(app, folder(appRoot.path, "src"))
        PsiTestUtil.addSourceRoot(app, folder(appRoot.path, "test"), true)
        PsiTestUtil.addSourceRoot(app, folder(appRoot.path, "resources"), JavaResourceRootType.RESOURCE)
        val generated = JpsJavaExtensionService.getInstance().createSourceRootProperties("", true)
        PsiTestUtil.addSourceRoot(app, folder(appRoot.path, "gen"), JavaSourceRootType.SOURCE, generated)
        PsiTestUtil.addExcludedRoot(app, folder(appRoot.path, "build"))

        val coreDir = outsideProject("core")
        coreRoot = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(coreDir))
        val core = javaModule("core", coreDir.path)
        PsiTestUtil.addContentRoot(core, coreRoot)
        PsiTestUtil.addSourceRoot(core, folder(coreRoot.path, "src"))
        val util = javaModule("util", directory(base.path, "util"))
        val testkit = javaModule("testkit", directory(base.path, "testkit"))

        ModuleRootModificationUtil.addDependency(app, core, DependencyScope.COMPILE, true)
        ModuleRootModificationUtil.addDependency(app, util, DependencyScope.TEST, false)
        ModuleRootModificationUtil.addDependency(core, util, DependencyScope.RUNTIME, false)
        ModuleRootModificationUtil.addDependency(util, core, DependencyScope.PROVIDED, false)
        ModuleRootModificationUtil.addDependency(testkit, app)
        ModuleRootModificationUtil.addDependency(app, testkit, DependencyScope.TEST, false)
        val jars = outsideProject("jars")
        val jar = LibraryJars.classesJar(jars, "lib", mapOf("lib/L.java" to "package lib; public class L {}"))
        val jarRoot = JarFileSystem.getInstance()
            .getJarRootForLocalFile(checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(jar)))!!
        PsiTestUtil.addProjectLibrary(app, "lib", listOf(jarRoot), emptyList())
        tools = ToolCalls(project)
    }

    override fun tearDown() {
        try {
            tools.dispose()
        } finally {
            try {
                super.tearDown()
            } finally {
                outside.forEach { it.deleteRecursively() }
            }
        }
    }

    private fun javaModule(name: String, path: String): Module =
        createModuleAt(name, project, JavaModuleType.getModuleType(), File(path).toPath())

    private fun directory(parent: String, name: String): String = File(parent, name).apply { mkdirs() }.path

    private fun folder(parent: String, name: String): VirtualFile =
        checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(directory(parent, name))))

    private fun outsideProject(name: String): File =
        File(File(project.basePath!!).parentFile, "mc-$name-${getTestName(true)}").apply {
            deleteRecursively()
            mkdirs()
            outside += this
        }

    private fun call(
        action: ModulesActionInput,
        module: String? = null,
        direction: DirectionInput? = null,
        depth: Int? = null,
        includeLibraries: Boolean? = null,
        ignoreTestScope: Boolean? = null,
    ): ModulesOutput = tools.call(TOOL) {
        toolset.modules(
            action,
            module,
            direction,
            depth,
            includeLibraries,
            ignoreTestScope,
            limit = null,
            offset = null,
        )
    }

    private fun ModulesOutput.ours() = edges!!.filter { it.from in names }

    fun testListShowsContentRootsInsideAndNextToTheProjectDirectoryWithTheirFolders() {
        val modules = call(ModulesActionInput.LIST).modules!!.filter { it.name in names }
        assertEquals(listOf("app", "core", "testkit", "util"), modules.map { it.name })

        val app = modules.first { it.name == "app" }
        assertEquals("JAVA_MODULE", app.type)
        assertNotNull("the Java plugin reports a language level", app.languageLevel)
        assertNull("no build system imported the module", app.buildSystem)
        val appRoot = app.contentRoots.single()
        assertEquals("app", appRoot.path)
        assertEquals(project.projectDirectory.relativizeIfPossible(folder(project.basePath!!, "app")), appRoot.path)
        assertNull(appRoot.outsideProjectDir)
        assertEquals(
            listOf(
                "app/gen" to "source",
                "app/src" to "source",
                "app/test" to "test",
                "app/resources" to "resource",
                "app/build" to "excluded",
            ),
            appRoot.folders.map { it.path to it.kind },
        )
        assertEquals(listOf("app/gen"), appRoot.folders.filter { it.generated == true }.map { it.path })

        val coreRootOutput = modules.first { it.name == "core" }.contentRoots.single()
        assertEquals(true, coreRootOutput.outsideProjectDir)
        assertTrue(coreRootOutput.path, coreRootOutput.path.startsWith("../"))
        // The same form the bundled MCP Server's tools use, so read_file and the others accept it.
        assertEquals(project.projectDirectory.relativizeIfPossible(coreRoot), coreRootOutput.path)
        assertEquals(listOf("${coreRootOutput.path}/src"), coreRootOutput.folders.map { it.path })
    }

    fun testGraphHasScopesAndTheExportedFlagAndLibrariesOnRequest() {
        val whole = call(ModulesActionInput.GRAPH)
        assertEquals(
            listOf(
                "app>core compile exported",
                "app>testkit test",
                "app>util test",
                "core>util runtime",
                "testkit>app compile",
                "util>core provided",
            ),
            whole.ours().map { "${it.from}>${it.to} ${it.scope}" + if (it.exported) " exported" else "" },
        )
        assertTrue(whole.ours().all { it.kind == "module" && it.depth == null })

        val library = call(ModulesActionInput.GRAPH, includeLibraries = true).ours().single { it.kind == "library" }
        assertEquals("app" to "lib", library.from to library.to)
        assertNull("a plain jar library has no Maven coordinates", library.coordinates)
    }

    fun testWalksInBothDirectionsWithDepth() {
        val down = call(ModulesActionInput.GRAPH, module = "app", depth = 1)
        assertEquals("app", down.root)
        assertEquals("dependencies", down.direction)
        assertEquals(
            listOf("app>core@1", "app>testkit@1", "app>util@1"),
            down.edges!!.map {
                "${it.from}>${it.to}@${it.depth}"
            },
        )

        val up = call(ModulesActionInput.GRAPH, module = "util", direction = DirectionInput.DEPENDENTS)
        assertEquals(
            listOf("app>util@1", "core>util@1", "app>core@2", "testkit>app@2", "util>core@2", "app>testkit@3"),
            up.edges!!.map { "${it.from}>${it.to}@${it.depth}" },
        )
    }

    fun testCyclesFindTheRealCycleAndTheTestOnlyOneUnlessTestScopeIsIgnored() {
        val all = call(ModulesActionInput.CYCLES)
        assertEquals(listOf(listOf("app", "testkit"), listOf("core", "util")), all.cycles!!.map { it.modules })
        assertEquals(listOf("core", "util", "core"), all.cycles[1].cycle)
        assertEquals(listOf("runtime", "provided"), all.cycles[1].edges.map { it.scope })

        val production = call(ModulesActionInput.CYCLES, ignoreTestScope = true)
        assertEquals(listOf(listOf("core", "util")), production.cycles!!.map { it.modules })
        assertEquals(true, production.ignoredTestScope)
    }

    /** As a client calls it: JSON arguments through the server's own tool, enum values in lower case. */
    fun testServerCallWithJsonArgumentsAndARejectedParameter() {
        val result = tools.callJson(
            TOOL,
            JsonObject(
                mapOf(
                    "action" to JsonPrimitive("graph"),
                    "module" to JsonPrimitive("core"),
                    "depth" to JsonPrimitive(1),
                ),
            ),
        )
        assertFalse(result.toString(), result.isError)
        val json = Json.parseToJsonElement(
            result.content.filterIsInstance<McpToolCallResultContent.Text>().single().text,
        )
        val edge = json.jsonObject.getValue("edges").jsonArray.single().jsonObject
        assertEquals("util", edge.getValue("to").jsonPrimitive.content)
        assertEquals("runtime", edge.getValue("scope").jsonPrimitive.content)
        assertEquals(1, edge.getValue("depth").jsonPrimitive.int)

        val listWithDepth = JsonObject(mapOf("action" to JsonPrimitive("list"), "depth" to JsonPrimitive(1)))
        val rejected = Assert.assertThrows(McpExpectedError::class.java) { tools.callJson(TOOL, listWithDepth) }
        assertTrue(rejected.message, "does not take depth" in rejected.message!!)
    }

    private companion object {
        const val TOOL = "mc_modules"
    }
}
