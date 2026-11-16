package com.github.sinlordep.mcpcompanion.common.paths.psi

import com.github.sinlordep.mcpcompanion.common.paths.ProjectPathRenderer
import com.github.sinlordep.mcpcompanion.common.symbols.FileOrigin
import com.intellij.openapi.project.BaseProjectDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.JdkOrderEntry
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager

/**
 * Maps client paths to files and files to output paths, consistently with the bundled MCP Server's tools: project
 * directories are the project root plus the IDE's base project directories (top-level content roots, also those
 * outside the root), and paths are rendered by [ProjectPathRenderer].
 */
class ProjectFiles(private val project: Project, val paths: ProjectPathRenderer) {

    /**
     * The file or directory a client means, or null when there is none. Accepts paths relative to the project root
     * (`..` allowed), absolute paths, archive entries (`/libs/x.jar!/pkg/Foo.java`) and VFS URLs (`file://`, `jar://`).
     * Does not check whether the file belongs to the project; see [originOf].
     */
    fun find(clientPath: String): VirtualFile? {
        if (URL.containsMatchIn(clientPath)) return VirtualFileManager.getInstance().findFileByUrl(clientPath.trim())
        val absolute = paths.resolve(clientPath)
        if (ARCHIVE_SEPARATOR in absolute) return JarFileSystem.getInstance().findFileByPath(absolute)
        // Inside the project root, look up relative to it, which also works for non-local file systems (e.g. the
        // in-memory one of light tests); anything else is a local file.
        return paths.relativize(absolute)?.let { project.guessProjectDir()?.findFileByRelativePath(it) }
            ?: LocalFileSystem.getInstance().findFileByPath(absolute)
    }

    /** Where [file] belongs. Call inside a read action. */
    fun originOf(file: VirtualFile): FileOrigin {
        val index = ProjectFileIndex.getInstance(project)
        return when {
            index.isInContent(file) -> FileOrigin.PROJECT
            !index.isInLibrary(file) -> FileOrigin.OTHER
            index.getOrderEntriesForFile(file).any { it is JdkOrderEntry } -> FileOrigin.SDK
            index.isInLibrarySource(file) -> FileOrigin.LIBRARY_SOURCE
            else -> FileOrigin.LIBRARY_CLASS
        }
    }

    /** [file]'s path as tool output shows it. */
    fun pathOf(file: VirtualFile): String = paths.render(file.path)

    companion object {
        private val URL = Regex("^\\s*[a-z][a-z0-9+.-]+://")
        private const val ARCHIVE_SEPARATOR = "!/"

        fun of(project: Project): ProjectFiles {
            val root = project.guessProjectDir()?.path ?: project.basePath ?: "/"
            val directories = with(BaseProjectDirectories) { project.getBaseDirectories() }.map { it.path }
            return ProjectFiles(
                project,
                ProjectPathRenderer(root, SystemInfoRt.isFileSystemCaseSensitive, directories),
            )
        }
    }
}
