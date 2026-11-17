package com.github.sinlordep.mcpcompanion.common.paths

/**
 * Converts between client paths, absolute paths and paths relative to the project root, the way the bundled MCP
 * Server's own tools do, so that paths can travel between our tools and theirs (`read_file` and others):
 *
 * - a file under the project root or under any other project directory (e.g. a content root next to the root) is
 *   rendered relative to the root, with `..` where needed (`../sibling/src/Foo.java`);
 * - an entry of an archive keeps its absolute archive form (`/libs/x.jar!/pkg/Foo.java`);
 * - anything else is rendered as a normalised absolute path.
 *
 * Pure: it parses paths lexically (see [PathSyntax]) and never touches the file system, so Windows and UNC paths are
 * understood on any OS. Results use `/` separators, the form `VirtualFile.path` uses.
 *
 * @param caseSensitive whether file names on the project's file system are case-sensitive; decides how directory
 *   prefixes are compared. Drive letters are always compared case-insensitively.
 * @param projectDirectories absolute paths of further project directories (content roots outside [projectRoot]).
 */
class ProjectPathRenderer(
    projectRoot: String,
    private val caseSensitive: Boolean,
    projectDirectories: Collection<String> = emptyList(),
) {

    private val root: PathSyntax = absolute(projectRoot)
    private val directories: List<PathSyntax> = projectDirectories.map(::absolute)

    /** How [absolutePath] appears in tool output; see the class comment. */
    fun render(absolutePath: String): String {
        val entry = archiveEntry(absolutePath)
        val path = PathSyntax.parse(absolutePath)
        val inRoot = relativeSegments(path, root)
        return when {
            entry != null -> "${entry.first}$ARCHIVE_SEPARATOR${entry.second}"
            inRoot != null -> inRoot.joinToString("/").ifEmpty { "." }
            directories.any { relativeSegments(path, it) != null } -> relativeToRoot(path) ?: path.toString()
            else -> path.toString()
        }
    }

    /**
     * The normalised absolute form of a client path that is absolute or relative to the project root (`..` allowed).
     * Archive entries (`x.jar!/pkg/Foo.java`) resolve the archive part and keep the entry.
     */
    fun resolve(clientPath: String): String {
        archiveEntry(clientPath)?.let { (archive, entry) -> return "${resolve(archive)}$ARCHIVE_SEPARATOR$entry" }
        val path = PathSyntax.parse(clientPath)
        return if (path.isAbsolute) path.toString() else PathSyntax.parse("$root/$path").toString()
    }

    /** [absolutePath] relative to the project root (`""` for the root itself), or null when it lies outside. */
    fun relativize(absolutePath: String): String? =
        relativeSegments(PathSyntax.parse(absolutePath), root)?.joinToString("/")

    private fun relativeSegments(path: PathSyntax, directory: PathSyntax): List<String>? {
        val inside = path.isAbsolute &&
            path.prefix.equals(directory.prefix, ignoreCase = true) &&
            path.segments.size >= directory.segments.size &&
            directory.segments.indices.all { sameSegment(path.segments[it], directory.segments[it]) }
        return if (inside) path.segments.drop(directory.segments.size) else null
    }

    /** [path] relative to the root with leading `..`, or null when they share no prefix (another drive or share). */
    private fun relativeToRoot(path: PathSyntax): String? {
        if (!path.prefix.equals(root.prefix, ignoreCase = true)) return null
        val common = root.segments.indices.takeWhile {
            it < path.segments.size && sameSegment(path.segments[it], root.segments[it])
        }.size
        val ups = List(root.segments.size - common) { ".." }
        return (ups + path.segments.drop(common)).joinToString("/")
    }

    private fun sameSegment(a: String, b: String): Boolean = a.equals(b, ignoreCase = !caseSensitive)

    private companion object {
        const val ARCHIVE_SEPARATOR = "!/"

        fun absolute(path: String): PathSyntax = PathSyntax.parse(path).also {
            require(it.isAbsolute) { "A project directory must be absolute: $path" }
        }

        /** Splits `archive!/entry`; null for a path that is not inside an archive. */
        fun archiveEntry(path: String): Pair<String, String>? {
            val unified = path.replace('\\', '/')
            val index = unified.indexOf(ARCHIVE_SEPARATOR)
            if (index < 0) return null
            val archive = PathSyntax.parse(unified.substring(0, index)).toString()
            return archive to unified.substring(index + ARCHIVE_SEPARATOR.length)
        }
    }
}
