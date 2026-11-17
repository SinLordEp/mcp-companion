package com.github.sinlordep.mcpcompanion.common.paths

/**
 * A path split into its absolute prefix and its segments, after lexical normalisation. Pure: no file system access,
 * so symbolic links are not resolved. Understands Unix (`/a/b`), Windows drive (`C:\a\b`, `c:/a/b`) and UNC
 * (`\\server\share\a`) paths whatever the current OS; backslashes count as separators.
 *
 * Normalisation drops `.` segments and empty segments and applies `..`. In an absolute path `..` never climbs above
 * the prefix (`/..` is `/`, `//server/share/..` is `//server/share`); in a relative path leading `..` are kept.
 */
internal data class PathSyntax(val prefix: String, val segments: List<String>) {

    val isAbsolute: Boolean get() = prefix.isNotEmpty()

    override fun toString(): String = when {
        segments.isEmpty() -> prefix.ifEmpty { "." }
        prefix.isEmpty() || prefix.endsWith("/") -> prefix + segments.joinToString("/")
        else -> prefix + "/" + segments.joinToString("/")
    }

    companion object {
        private val DRIVE = Regex("^([A-Za-z]):(/|$)")

        fun parse(path: String): PathSyntax {
            val unified = path.replace('\\', '/')
            val (prefix, rest) = splitPrefix(unified)
            return PathSyntax(prefix, normalise(rest.split('/'), absolute = prefix.isNotEmpty()))
        }

        private fun splitPrefix(path: String): Pair<String, String> {
            val drive = DRIVE.find(path)
            return when {
                drive != null -> "${drive.groupValues[1].uppercase()}:/" to path.substring(drive.range.last + 1)
                path.startsWith("//") -> uncPrefix(path.removePrefix("//"))
                path.startsWith("/") -> "/" to path.removePrefix("/")
                else -> "" to path
            }
        }

        /** `//server/share` is the root of a UNC path; `..` cannot leave it. */
        private fun uncPrefix(afterSlashes: String): Pair<String, String> {
            val parts = afterSlashes.split('/', limit = 3)
            val root = parts.take(2).joinToString("/")
            return "//$root" to parts.getOrElse(2) { "" }
        }

        private fun normalise(raw: List<String>, absolute: Boolean): List<String> {
            val result = ArrayList<String>()
            for (segment in raw) {
                when {
                    segment.isEmpty() || segment == "." -> Unit
                    segment != ".." -> result += segment
                    result.isNotEmpty() && result.last() != ".." -> result.removeAt(result.lastIndex)
                    !absolute -> result += segment
                }
            }
            return result
        }
    }
}
