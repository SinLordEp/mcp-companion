package mcpcompanion.e2e

import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.process.CommandLineArgumentProvider

/**
 * JVM options of the sandbox IDE: the bundled MCP server force-enabled on a fixed loopback port, every project trusted,
 * a private Gradle user home, and optionally the AWT toolkit. `XToolkit` keeps the IDE on `$DISPLAY` (e.g. a private
 * Xvfb): JBR 25 otherwise picks the Wayland toolkit and opens its windows on the desktop session.
 */
class McpE2eJvmArguments(
    @get:Input val port: Provider<String>,
    @get:Input val awtToolkit: Provider<String>,
    @get:Input val gradleUserHome: Provider<String>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOfNotNull(
        "-Didea.mcp.server.force.enable=true",
        "-Didea.mcp.server.force.port=${port.get()}",
        "-Didea.trust.all.projects=true",
        "-Dgradle.user.home=${gradleUserHome.get()}",
        awtToolkit.get().takeIf { it.isNotBlank() }?.let { "-Dawt.toolkit.name=$it" },
    )
}

/** The program argument of the sandbox IDE: the one project it opens. */
class McpE2eOpenedProject(@get:Input val path: Provider<String>) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf(path.get())
}
