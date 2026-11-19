package mcpcompanion.e2e

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.io.File

/**
 * Prepares the sandbox IDE of `runIdeForMcpE2e` before every start: fresh copies of the fixture projects under
 * `build/` (so that edits of an earlier run, e.g. `mc_safe_delete apply`, are gone), JUnit 4 for the test fixtures
 * taken from the sandbox IDE itself (nothing is downloaded), the sandbox's brave mode and its JDK table. Only the
 * sandbox's own configuration is written, never the owner's IDE settings.
 */
@UntrackedTask(because = "It replaces the fixtures and sandbox options on every start of the sandbox IDE.")
abstract class PrepareMcpE2eSandbox : DefaultTask() {
    @get:Input
    abstract val fixture: Property<String>

    @get:Input
    abstract val launchConfirmation: Property<String>

    /** Unpacked Gradle distribution for the Gradle fixture; by default the newest one under [gradleUserHome]. */
    @get:Input
    @get:Optional
    abstract val gradleHome: Property<String>

    /** Maven fixture: whether the IDE delegates building to Maven (`-PmcpE2eMavenBuild=maven`) or builds it itself. */
    @get:Input
    abstract val mavenDelegatedBuild: Property<Boolean>

    /** JPS fixture: whether its copy becomes a Git repository with a few commits (`-PmcpE2eVcs=git`). */
    @get:Input
    abstract val gitRepository: Property<Boolean>

    @get:Internal
    abstract val gradleUserHome: DirectoryProperty

    /** The project's `src` directory, which holds the fixture sources. */
    @get:Internal
    abstract val fixtureSources: DirectoryProperty

    /** The project's `build` directory, which receives the throwaway fixture copies. */
    @get:Internal
    abstract val workDirectory: DirectoryProperty

    /** The sandbox IDE's installation (for its JUnit 4 jars). */
    @get:Internal
    abstract val platformDirectory: DirectoryProperty

    /** The runtime the sandbox IDE runs on; it becomes the fixtures' JDK. */
    @get:Internal
    abstract val runtimeDirectory: DirectoryProperty

    @get:Internal
    abstract val sandboxConfigDirectory: DirectoryProperty

    @get:Internal
    abstract val sandboxSystemDirectory: DirectoryProperty

    /** The project the sandbox IDE opens. */
    @get:Internal
    val openedProject: Provider<Directory>
        get() = workDirectory.zip(fixture) { build, kind ->
            build.dir(McpE2eFixture.parse(kind).directoryName)
        }

    @TaskAction
    fun prepare() {
        val layout = McpE2eLayout(fixtureSources.get().asFile, workDirectory.get().asFile)
        val junit = junitJars()
        prepareJpsFixture(layout, junit)
        when (McpE2eFixture.parse(fixture.get())) {
            McpE2eFixture.JPS -> if (gitRepository.get()) GitFixture.initialise(layout.jpsFixture)
            McpE2eFixture.GRADLE -> prepareGradleFixture(layout, junit)
            McpE2eFixture.MAVEN -> prepareMavenFixture(layout)
        }
        val config = sandboxConfigDirectory.get().asFile
        BraveModeOption.write(
            config.resolve("options/mcpServer.xml"),
            LaunchConfirmation.parse(launchConfirmation.get()).braveMode,
        )
        SandboxJdkTable.write(config.resolve("options/jdk.table.xml"), runtimeDirectory.get().asFile)
    }

    /** JUnit 4 and Hamcrest from the sandbox IDE's JUnit plugin, by the file names the fixtures refer to. */
    private fun junitJars(): Map<String, File> {
        val directory = platformDirectory.get().asFile.resolve("plugins/junit/lib")
        return mapOf(
            "junit-4.13.2.jar" to directory.resolve("intellij.libraries.junit4.jar"),
            "hamcrest.jar" to directory.resolve("intellij.libraries.hamcrest.jar"),
        )
    }

    private fun prepareJpsFixture(layout: McpE2eLayout, junit: Map<String, File>) {
        replaceWithCopy(layout.jpsSource, layout.jpsFixture)
        replaceWithCopy(layout.jpsSiblingSource, layout.jpsSibling)
        layout.jpsFixture.resolve("README.md").writeText("Throwaway project opened by runIdeForMcpE2e.\n")
        junit.forEach { (name, jar) -> jar.copyTo(layout.jpsFixture.resolve("lib/$name"), overwrite = true) }
        // The Kotlin standard library of the sandbox IDE's Kotlin plugin, for the kotlinfix module (mc_run start_at).
        platformDirectory.get().asFile.resolve("plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
            .copyTo(layout.jpsFixture.resolve("lib/kotlin-stdlib.jar"), overwrite = true)
    }

    private fun prepareGradleFixture(layout: McpE2eLayout, junit: Map<String, File>) {
        val home = gradleHome.orNull
            ?: GradleFixtureFiles.newestUnpackedDistribution(gradleUserHome.get().asFile)?.absolutePath
            ?: throw GradleException("no unpacked Gradle distribution; pass -PmcpE2eGradleHome=<dir>")
        if (!File(home, "bin/gradle").isFile) throw GradleException("no unpacked Gradle distribution: $home")
        replaceWithCopy(layout.gradleSiblingSource, layout.gradleSibling)
        forgetCachedModel(layout.gradleFixture)
        replaceWithCopy(layout.gradleSource, layout.gradleFixture)
        junit.forEach { (name, jar) -> jar.copyTo(layout.gradleFixture.resolve("testlibs/$name"), overwrite = true) }
        layout.gradleFixture.resolve(".idea/gradle.xml").writeText(GradleFixtureFiles.gradleSettings(home))
    }

    private fun prepareMavenFixture(layout: McpE2eLayout) {
        val repository = layout.mavenRepository.apply { mkdirs() }
        forgetCachedModel(layout.mavenFixture)
        replaceWithCopy(layout.mavenSource, layout.mavenFixture)
        // The source has no .idea directory (git ignores it there).
        val settings = layout.mavenFixture.resolve(".idea/mcp-e2e-maven-settings.xml")
        settings.parentFile.mkdirs()
        settings.writeText(MavenFixtureFiles.mavenSettings(repository))
        layout.mavenFixture.resolve(".idea/workspace.xml")
            .writeText(MavenFixtureFiles.workspace(repository, settings, mavenDelegatedBuild.get()))
    }

    /** Drops the sandbox's cached model of an earlier run of [project], so that the IDE imports it afresh. */
    private fun forgetCachedModel(project: File) {
        sandboxSystemDirectory.get().asFile.resolve("projects").listFiles().orEmpty()
            .filter { it.name.startsWith("${project.name}.") }
            .forEach { it.deleteRecursively() }
    }

    private fun replaceWithCopy(source: File, target: File) {
        target.deleteRecursively()
        source.copyRecursively(target)
    }
}
