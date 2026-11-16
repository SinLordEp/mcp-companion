package com.github.sinlordep.mcpcompanion.common.paths

/**
 * Converts between client paths, absolute paths and paths relative to the project root.
 *
 * Pure: it parses paths lexically (see [PathSyntax]) and never touches the file system, so Windows and UNC paths are
 * understood on any OS. Results use `/` separators, the form `VirtualFile.path` uses. A path counts as inside the
 * project only after normalisation, so `../x` or `<root>/../x` are outside.
 *
 * @param caseSensitive whether file names on the project's file system are case-sensitive; decides how the root
 *   prefix is compared. Drive letters are always compared case-insensitively.
 */
class ProjectPathRenderer(projectRoot: String, private val caseSensitive: Boolean) {

    private val root: PathSyntax = PathSyntax.parse(projectRoot).also {
        require(it.isAbsolute) { "The project root must be absolute: $projectRoot" }
    }

    /** [absolutePath] relative to the project root; normalised but absolute when it lies outside the root. */
    fun render(absolutePath: String): String {
        val path = PathSyntax.parse(absolutePath)
        return relativeSegments(path)?.let { it.joinToString("/").ifEmpty { "." } } ?: path.toString()
    }

    /** The normalised absolute form of a client path that is absolute or relative to the project root. */
    fun resolve(clientPath: String): String {
        val path = PathSyntax.parse(clientPath)
        return if (path.isAbsolute) path.toString() else PathSyntax.parse("$root/$path").toString()
    }

    /** [absolutePath] relative to the project root (`""` for the root itself), or null when it lies outside. */
    fun relativize(absolutePath: String): String? = relativeSegments(PathSyntax.parse(absolutePath))?.joinToString("/")

    private fun relativeSegments(path: PathSyntax): List<String>? {
        val inside = path.isAbsolute &&
            path.prefix.equals(root.prefix, ignoreCase = true) &&
            path.segments.size >= root.segments.size &&
            root.segments.indices.all { path.segments[it].equals(root.segments[it], ignoreCase = !caseSensitive) }
        return if (inside) path.segments.drop(root.segments.size) else null
    }
}
