package com.github.sinlordep.mcpcompanion.toolsets

import com.github.sinlordep.mcpcompanion.common.paths.psi.ProjectFiles
import com.github.sinlordep.mcpcompanion.testing.LibraryJars
import com.github.sinlordep.mcpcompanion.testing.ToolCalls
import com.github.sinlordep.mcpcompanion.toolsets.hierarchy.HierarchyDirectionInput
import com.github.sinlordep.mcpcompanion.toolsets.hierarchy.TypeHierarchyToolset
import com.github.sinlordep.mcpcompanion.toolsets.implementations.FindImplementationsToolset
import com.github.sinlordep.mcpcompanion.toolsets.safedelete.SafeDeleteActionInput
import com.github.sinlordep.mcpcompanion.toolsets.safedelete.SafeDeleteToolset
import com.github.sinlordep.mcpcompanion.toolsets.symbols.OriginOutput
import com.github.sinlordep.mcpcompanion.toolsets.usages.FindUsagesToolset
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.util.projectDirectories
import com.intellij.mcpserver.util.projectDirectory
import com.intellij.mcpserver.util.relativizeIfPossible
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.VfsTestUtil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Path

/**
 * The location model on a real file system: a content root outside the project directory, a library with attached
 * sources and a class-only library. Paths we emit must be the ones the bundled MCP Server's tools emit and accept.
 */
class ProjectLocationsTest : HeavyPlatformTestCase() {

    private lateinit var tools: ToolCalls
    private lateinit var helper: VirtualFile
    private lateinit var librarySource: VirtualFile
    private lateinit var classOnly: VirtualFile
    private val siblingDirs = mutableListOf<File>()

    private inline fun <reified T : McpToolset> toolset(): T =
        McpToolset.EP.extensionList.filterIsInstance<T>().single()

    override fun setUp() {
        super.setUp()
        val base = getOrCreateProjectBaseDir()
        VfsTestUtil.createFile(base, "app/Main.java", MAIN)
        PsiTestUtil.addSourceContentToRoots(module, base)

        // Next to the project directory, like a Gradle subproject at ../sibling.
        val siblingDir = outsideProject("sibling")
        helper = createLocalFile(siblingDir, "sib/Helper.java", HELPER)
        val sibling = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(siblingDir))
        PsiTestUtil.addSourceContentToRoots(module, sibling)

        val jars = outsideProject("jars")
        val shapeSources = mapOf("lib/LibShape.java" to LIB_SHAPE)
        val shapeClasses = jarRoot(LibraryJars.classesJar(jars, "lib", shapeSources))
        val shapeSourceRoot = jarRoot(LibraryJars.sourcesJar(jars, "lib", shapeSources))
        PsiTestUtil.addProjectLibrary(module, "lib", listOf(shapeClasses), listOf(shapeSourceRoot))
        val onlyClasses = jarRoot(LibraryJars.classesJar(jars, "cls", mapOf("lib2/LibOnly.java" to LIB_ONLY)))
        PsiTestUtil.addProjectLibrary(module, "cls", listOf(onlyClasses), emptyList())
        librarySource = checkNotNull(shapeSourceRoot.findFileByRelativePath("lib/LibShape.java"))
        classOnly = checkNotNull(onlyClasses.findFileByRelativePath("lib2/LibOnly.class"))

        IndexingTestUtil.waitUntilIndexesAreReady(project)
        tools = ToolCalls(project)
        assertFalse(
            "the sibling root must lie outside the project directory",
            siblingDir.toPath().startsWith(Path.of(project.basePath!!)),
        )
    }

    override fun tearDown() {
        try {
            tools.dispose()
        } finally {
            try {
                super.tearDown()
            } finally {
                siblingDirs.forEach { it.deleteRecursively() }
            }
        }
    }

    /** A fresh directory next to the project directory, so neither inside it nor in any content root. */
    private fun outsideProject(name: String): File =
        File(File(project.basePath!!).parentFile, "mc-$name-${getTestName(true)}").apply {
            deleteRecursively()
            mkdirs()
            siblingDirs += this
        }

    private fun createLocalFile(directory: File, path: String, text: String): VirtualFile {
        val file = File(directory, path).apply {
            parentFile.mkdirs()
            writeText(text)
        }
        return checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)) { "no VFS file for $file" }
    }

    private fun jarRoot(jar: File): VirtualFile {
        val local = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(jar))
        return checkNotNull(JarFileSystem.getInstance().getJarRootForLocalFile(local))
    }

    private val ours get() = ProjectFiles.of(project)

    private fun relativeToBase(file: VirtualFile): String =
        Path.of(project.basePath!!).relativize(Path.of(file.path)).toString().replace(File.separatorChar, '/')

    // ---- contract with the bundled MCP Server (what we mirror) ----

    fun testOfficialToolsCountContentRootsAsProjectDirectoriesAndRenderThemWithDotDot() {
        val siblingRoot = Path.of(helper.parent.parent.path)
        assertTrue(
            "official project directories: ${project.projectDirectories()}",
            siblingRoot in project.projectDirectories(),
        )
        val official = project.projectDirectory.relativizeIfPossible(helper)
        assertTrue(official, official.startsWith("../"))
        assertEquals(official, ours.pathOf(helper))
        // Archive entries are where we deliberately differ: the official tools relativise them too
        // (../../.gradle/...x.jar!/...), which is long and fragile; we emit the absolute entry, which
        // read_file accepts.
        val officialEntry = project.projectDirectory.relativizeIfPossible(librarySource)
        assertTrue(
            officialEntry,
            officialEntry.startsWith("../") && officialEntry.endsWith("lib-sources.jar!/lib/LibShape.java"),
        )
        assertEquals(librarySource.path, ours.pathOf(librarySource))
    }

    fun testOfficialReadFileAcceptsThePathsWeEmit() {
        for (path in listOf(ours.pathOf(helper), ours.pathOf(librarySource), ours.pathOf(classOnly))) {
            val tool = tools.served("read_file")
            val pathParameter = tool.descriptor.inputSchema.requiredProperties.single()
            val result = tools.callJson("read_file", JsonObject(mapOf(pathParameter to JsonPrimitive(path))))
            val text = result.content.filterIsInstance<McpToolCallResultContent.Text>().joinToString { it.text }
            assertFalse("read_file($path): $text", result.isError)
            assertTrue("read_file($path): $text", "class" in text || "interface" in text)
        }
    }

    // ---- our tools ----

    fun testSiblingRootFilesAreProjectFilesRenderedRelativeToTheProjectDirectory() {
        val output = tools.call("mc_find_usages") {
            toolset<FindUsagesToolset>().findUsages(qualifiedName = "sib.Helper")
        }
        assertEquals(relativeToBase(helper), output.symbol.path)
        assertEquals(OriginOutput.PROJECT, output.symbol.origin)
        assertEquals(listOf("app/Main.java"), output.usages.map { it.path })
        assertEquals(OriginOutput.PROJECT, output.usages.single().origin)
    }

    fun testLibrarySymbolsLiveInTheirSourcesJarAndAreFoundFromProjectCode() {
        val output = tools.call("mc_find_usages") {
            toolset<FindUsagesToolset>().findUsages(qualifiedName = "lib.LibShape")
        }
        assertEquals(librarySource.path, output.symbol.path)
        assertTrue(output.symbol.path!!.endsWith("lib-sources.jar!/lib/LibShape.java"))
        assertEquals(OriginOutput.LIBRARY_SOURCE, output.symbol.origin)
        assertEquals(setOf("app/Main.java", relativeToBase(helper)), output.usages.map { it.path }.toSet())
    }

    fun testClassOnlyLibrarySymbolsPointIntoTheClassesJar() {
        val output = tools.call("mc_find_usages") {
            toolset<FindUsagesToolset>().findUsages(qualifiedName = "lib2.LibOnly")
        }
        assertEquals(OriginOutput.LIBRARY_CLASS, output.symbol.origin)
        assertEquals(classOnly.path, output.symbol.path)
        assertEquals(listOf("app/Main.java"), output.usages.map { it.path })
    }

    fun testImplementationsOfALibraryInterfaceInSiblingRootCode() {
        val output = tools.call("mc_find_implementations") {
            toolset<FindImplementationsToolset>().findImplementations(qualifiedName = "lib.LibShape")
        }
        assertEquals(listOf(relativeToBase(helper)), output.results.map { it.path })
    }

    fun testHierarchyShowsLibrarySupertypesWithTheirOrigin() {
        val output = tools.call("mc_type_hierarchy") {
            toolset<TypeHierarchyToolset>().typeHierarchy(
                qualifiedName = "sib.Helper",
                direction = HierarchyDirectionInput.UP,
            )
        }
        val shape = output.types.single { it.qualifiedName == "lib.LibShape" }
        assertEquals(OriginOutput.LIBRARY_SOURCE, shape.origin)
        assertEquals(listOf(shape.id), output.types[0].supertypes)
    }

    fun testPositionInputAcceptsEmittedPathsArchiveEntriesAndUrls() {
        val areaLine = LIB_SHAPE.lines().indexOfFirst { "area" in it } + 1
        val areaColumn = LIB_SHAPE.lines()[areaLine - 1].indexOf("area") + 1
        val helperLine = HELPER.lines().indexOfFirst { "class Helper" in it } + 1
        val helperColumn = HELPER.lines()[helperLine - 1].indexOf("Helper") + 1
        val cases = listOf(
            Triple(librarySource.path, areaLine, areaColumn) to "area",
            Triple(librarySource.url, areaLine, areaColumn) to "area",
            Triple(ours.pathOf(helper), helperLine, helperColumn) to "Helper",
            Triple(helper.url, helperLine, helperColumn) to "Helper",
            Triple(helper.path, helperLine, helperColumn) to "Helper",
        )
        for ((position, name) in cases) {
            val output = tools.call("mc_find_usages") {
                toolset<FindUsagesToolset>().findUsages(position.first, position.second, position.third)
            }
            assertEquals(position.first, name, output.symbol.name)
        }
    }

    fun testFilesOutsideProjectAndLibrariesAreRejected() {
        val file = createLocalFile(outsideProject("stray"), "Stray.java", "class Stray {}")
        assertEquals(
            "NOT_FOUND",
            tools.errorCode("mc_find_usages") {
                toolset<FindUsagesToolset>().findUsages(file.path, 1, 7)
            },
        )
    }

    fun testLibraryScopeAndSafeDeleteOfLibrarySymbols() {
        val output = tools.call("mc_find_usages") {
            toolset<FindUsagesToolset>().findUsages(qualifiedName = "lib.LibShape", scope = "project_and_libraries")
        }
        assertEquals(2, output.usages.size)
        assertEquals(
            "INVALID_ARGUMENT",
            tools.errorCode("mc_safe_delete") {
                toolset<SafeDeleteToolset>().safeDelete(SafeDeleteActionInput.PREVIEW, qualifiedName = "lib.LibShape")
            },
        )
    }

    private companion object {
        val MAIN = """
            package app;
            public class Main {
                double f(lib.LibShape s, sib.Helper h, lib2.LibOnly o) { return s.area() + h.area() + o.size(); }
            }
        """.trimIndent()
        val HELPER = """
            package sib;
            public class Helper implements lib.LibShape {
                public double area() { return 0; }
            }
        """.trimIndent()
        val LIB_SHAPE = """
            package lib;
            public interface LibShape {
                double area();
            }
        """.trimIndent()
        val LIB_ONLY = """
            package lib2;
            public class LibOnly {
                public int size() { return 0; }
            }
        """.trimIndent()
    }
}
