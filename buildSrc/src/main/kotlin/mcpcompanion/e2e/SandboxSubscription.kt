package mcpcompanion.e2e

import java.io.File

/**
 * The optional licence of the sandbox IDEs. IntelliJ IDEA's paid features (Spring, the HTTP Client and the rest of
 * `com.intellij.modules.ultimate`) work in a sandbox only with an `idea.key` licence file, which the IntelliJ Platform
 * Gradle Plugin copies into each sandbox it prepares; without one it disables them there. A developer with a
 * subscription names their own file in the Gradle property [PROPERTY] (in `~/.gradle/gradle.properties`, never in the
 * repository). Without it, or when it names no file, the sandboxes stay as they are.
 */
object SandboxSubscription {
    const val PROPERTY = "mcpCompanion.sandboxSubscriptionKey"

    /**
     * The licence file [value] names: an absolute path to an existing file. Null for an empty value, a relative path
     * (it would depend on the Gradle daemon's working directory) or a path that is no file. The path is never logged.
     */
    fun keyFile(value: String): File? =
        value.trim().takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isAbsolute && it.isFile }
}
