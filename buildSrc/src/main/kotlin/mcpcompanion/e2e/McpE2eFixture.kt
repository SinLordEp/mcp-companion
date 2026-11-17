package mcpcompanion.e2e

import org.gradle.api.GradleException

/** The project the sandbox IDE opens (`-PmcpE2eFixture=jps|gradle|maven`), as a copy in `build/<directoryName>`. */
enum class McpE2eFixture(val propertyValue: String, val directoryName: String) {
    /** `src/mcpE2eFixture`: plain IntelliJ modules with a second content root next to the project directory. */
    JPS("jps", "mcp-e2e-fixture"),

    /** `src/mcpE2eFixtureGradle`: build and run delegated to a local Gradle, with an included sibling build. */
    GRADLE("gradle", "mcp-e2e-gradle"),

    /** `src/mcpE2eFixtureMaven`: two Maven modules with a private local repository. */
    MAVEN("maven", "mcp-e2e-maven"),
    ;

    companion object {
        fun parse(value: String): McpE2eFixture = entries.firstOrNull { it.propertyValue == value }
            ?: throw GradleException(
                "mcpE2eFixture must be one of ${entries.joinToString { it.propertyValue }}, was $value",
            )
    }
}

/**
 * Whether MCP clients' launches are confirmed in the sandbox (`-PmcpE2eLaunchConfirmation=ask|skip`). It is the MCP
 * Server's brave mode: `skip` turns it on, so scripted rounds start processes without anyone at the display.
 */
enum class LaunchConfirmation(val propertyValue: String, val braveMode: Boolean) {
    ASK("ask", braveMode = false),
    SKIP("skip", braveMode = true),
    ;

    companion object {
        fun parse(value: String): LaunchConfirmation = entries.firstOrNull { it.propertyValue == value }
            ?: throw GradleException("mcpE2eLaunchConfirmation must be ask or skip, was $value")
    }
}
