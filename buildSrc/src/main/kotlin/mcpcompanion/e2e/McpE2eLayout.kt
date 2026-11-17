package mcpcompanion.e2e

import java.io.File

/**
 * Where the fixture sources live (under `src/`) and where their throwaway copies and private caches go (under
 * `build/`). Nothing here points into the user's home, `~/.gradle` or `~/.m2`.
 */
class McpE2eLayout(private val sources: File, private val build: File) {
    val jpsSource: File get() = sources.resolve("mcpE2eFixture/project")
    val jpsSiblingSource: File get() = sources.resolve("mcpE2eFixture/sibling")
    val gradleSource: File get() = sources.resolve("mcpE2eFixtureGradle")
    val gradleSiblingSource: File get() = sources.resolve("mcpE2eFixtureGradleSibling")
    val mavenSource: File get() = sources.resolve("mcpE2eFixtureMaven")

    val jpsFixture: File get() = opened(McpE2eFixture.JPS)

    /** A second content root of the JPS fixture, outside its project directory. */
    val jpsSibling: File get() = build.resolve("mcp-e2e-sibling")
    val gradleFixture: File get() = opened(McpE2eFixture.GRADLE)

    /** The Gradle fixture includes the build next to it (a composite of sibling repositories). */
    val gradleSibling: File get() = build.resolve("mcp-e2e-gradle-sibling")
    val mavenFixture: File get() = opened(McpE2eFixture.MAVEN)

    /** Private local Maven repository, kept across runs so that plugins are downloaded once. */
    val mavenRepository: File get() = build.resolve("mcp-e2e-m2")

    fun opened(fixture: McpE2eFixture): File = build.resolve(fixture.directoryName)

    companion object {
        /** Gradle runs of the sandbox keep caches and daemons here (relative to `build/`), never in `~/.gradle`. */
        const val GRADLE_USER_HOME = "mcp-e2e-gradle-user-home"
    }
}
