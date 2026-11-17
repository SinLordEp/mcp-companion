package mcpcompanion.e2e

import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.process.CommandLineArgumentProvider

/**
 * JVM options of the sandbox IDE: the bundled MCP server force-enabled on a fixed loopback port, every project trusted,
 * a private Gradle user home, and optionally the AWT toolkit. `XToolkit` keeps the IDE on `$DISPLAY` (e.g. a private
 * Xvfb): JBR 25 otherwise picks the Wayland toolkit and opens its windows on the desktop session.
 *
 * [gradleHome], when set, is the unpacked local Gradle the Gradle fixture uses. IntelliJ IDEA 2026.3 no longer reads
 * `gradleHome` from `.idea/gradle.xml` (the property is transient; the home lives in the per-project cache), so it is
 * passed through the Gradle plugin's own headless defaults (`idea.gradle.distributionType`, `idea.gradle.home`),
 * which every project setting starts from, and the import runs offline (`idea.gradle.offline`): no Gradle
 * distribution and no dependency is downloaded.
 */
class McpE2eJvmArguments(
    @get:Input val port: Provider<String>,
    @get:Input val awtToolkit: Provider<String>,
    @get:Input val gradleUserHome: Provider<String>,
    @get:Input val gradleHome: Provider<String>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOfNotNull(
        "-Didea.mcp.server.force.enable=true",
        "-Didea.mcp.server.force.port=${port.get()}",
        "-Didea.trust.all.projects=true",
        "-Dgradle.user.home=${gradleUserHome.get()}",
        awtToolkit.get().takeIf { it.isNotBlank() }?.let { "-Dawt.toolkit.name=$it" },
    ) + gradleArguments(gradleHome.get())

    companion object {
        /** The JVM options that make the IDE use the local Gradle at [home] offline; none without a home. */
        fun gradleArguments(home: String): List<String> = if (home.isBlank()) {
            emptyList()
        } else {
            listOf("-Didea.gradle.distributionType=LOCAL", "-Didea.gradle.home=$home", "-Didea.gradle.offline=true")
        }
    }
}

/** The program argument of the sandbox IDE: the one project it opens. */
class McpE2eOpenedProject(@get:Input val path: Provider<String>) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf(path.get())
}
