import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
    id("dev.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

kotlin {
    // IntelliJ Platform 2026.3 (build 263) runs on Java 25 (product-info.json: minRequiredJavaVersion = 25).
    jvmToolchain(25)
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // kotlin-stdlib and kotlinx-serialization(-json) are provided by the platform at runtime and are not bundled:
    // kotlin.stdlib.default.dependency=false in gradle.properties, and no kotlinx-serialization library dependency here.

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2026.3")
        bundledPlugin("com.intellij.mcpServer")
        // Optional at runtime (META-INF/mcp-companion-java.xml); needed at compile time for the Java-only code.
        bundledPlugin("com.intellij.java")
        // Optional at runtime (META-INF/mcp-companion-maven.xml); needed at compile time for mc_sync's Maven gateway.
        bundledPlugin("org.jetbrains.idea.maven")
        // Optional at runtime (META-INF/mcp-companion-test-runner.xml); its content module intellij.platform.smRunner
        // holds the test tree API of mc_run's tests action.
        bundledPlugin("com.intellij.platform.testRunner")
        testFramework(TestFrameworkType.Platform)
        testBundledPlugins("com.intellij.modules.ultimate")
        pluginVerifier()
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // Only the latest major IDE version is supported; see docs/PLAN.md section 3.
            sinceBuild = "263"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            // The IDE the plugin is built against (IntelliJ IDEA 2026.3). Add the next EAP here once it exists.
            current()
        }
    }
}

// ---- Quality gates (all part of `./gradlew check`) ---------------------------------------------------------------

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
    source.setFrom(files("src/main/kotlin", "src/test/kotlin", "src/architectureTest/kotlin"))
}

ktlint {
    version = "1.8.0"
}

// Architecture rules (docs/PLAN.md section 5.1) run as a plain JVM test suite: Konsist only parses the sources,
// so it needs neither the IDE nor the platform test framework on its classpath.
testing {
    suites {
        register<JvmTestSuite>("architectureTest") {
            useJUnitJupiter()
            dependencies {
                // kotlin.stdlib.default.dependency=false (the plugin gets it from the IDE) also applies here.
                implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
                implementation("com.lemonappdev:konsist:1.0.0-beta.1")
            }
            targets.configureEach {
                testTask.configure {
                    // Konsist reads the production sources at run time; without this the task stays UP-TO-DATE
                    // when only src/main changes.
                    inputs.files(fileTree("src/main")).withPropertyName("productionSources")
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("architectureTest"))
}

// ---- Sandbox IDE for manual and end-to-end checks ------------------------------------------------------------------

// The bundled MCP server is force-enabled on a fixed loopback port (no consent dialog) and only a throwaway project
// under build/ is opened: a fresh copy of src/mcpE2eFixture (a small Java module with a second content root at
// ../mcp-e2e-sibling).
//   ./gradlew runIdeForMcpE2e [-PmcpE2ePort=64399] [-PmcpE2eAwtToolkit=XToolkit]
// Endpoints: http://127.0.0.1:<port>/sse (SSE) and http://127.0.0.1:<port>/stream (Streamable HTTP).
// mcpE2eAwtToolkit=XToolkit keeps the IDE on $DISPLAY (e.g. a private Xvfb): JBR 25 otherwise picks the Wayland
// toolkit and opens its windows on the desktop session even when WAYLAND_DISPLAY is unset.
val mcpE2ePort = providers.gradleProperty("mcpE2ePort").orElse("64399")
val mcpE2eAwtToolkit = providers.gradleProperty("mcpE2eAwtToolkit").orElse("")
// -PmcpE2eFixture=gradle opens a Gradle project instead (build and run delegated to Gradle; src/mcpE2eFixtureGradle).
// It uses a Gradle distribution that is already unpacked on the machine, never a download:
// -PmcpE2eGradleHome=<dir>, by default the newest one under ~/.gradle/wrapper/dists.
val mcpE2eFixtureKind = providers.gradleProperty("mcpE2eFixture").orElse("jps")
val mcpE2eGradleHome = providers.gradleProperty("mcpE2eGradleHome").orElse(
    providers.systemProperty("user.home").map { home ->
        File(home, ".gradle/wrapper/dists").listFiles().orEmpty()
            .filter { it.name.matches(Regex("gradle-[0-9.]+-bin")) }
            .flatMap { dist -> dist.listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() } }
            .filter { it.resolve("bin/gradle").isFile }
            .maxByOrNull {
                it.name.removePrefix("gradle-").split('.').map(String::toInt).fold(0L) { a, n -> a * 1000 + n }
            }
            ?.absolutePath.orEmpty()
    },
)
val mcpE2eFixture = layout.buildDirectory.dir("mcp-e2e-fixture")
val mcpE2eGradleFixture = layout.buildDirectory.dir("mcp-e2e-gradle")
val mcpE2eSibling = layout.buildDirectory.dir("mcp-e2e-sibling")
val mcpE2eFixtureSource = layout.projectDirectory.dir("src/mcpE2eFixture")
val mcpE2eGradleFixtureSource = layout.projectDirectory.dir("src/mcpE2eFixtureGradle")
// The Gradle fixture includes the build next to it (a composite of sibling repositories, see its settings.gradle).
val mcpE2eGradleSibling = layout.buildDirectory.dir("mcp-e2e-gradle-sibling")
val mcpE2eGradleSiblingSource = layout.projectDirectory.dir("src/mcpE2eFixtureGradleSibling")
// Gradle runs by the sandbox keep their caches and daemons in a private user home, never in ~/.gradle.
// -PmcpE2eFixture=maven opens a two-module Maven project (src/mcpE2eFixtureMaven) whose Maven settings point to a
// private local repository under build/ (kept across runs), so nothing is read from or written to ~/.m2. The bundled
// Maven downloads the build plugins it needs into that repository.
val mcpE2eMavenFixture = layout.buildDirectory.dir("mcp-e2e-maven")
val mcpE2eMavenFixtureSource = layout.projectDirectory.dir("src/mcpE2eFixtureMaven")
val mcpE2eMavenRepository = layout.buildDirectory.dir("mcp-e2e-m2")
val mcpE2eGradleUserHome = layout.buildDirectory.dir("mcp-e2e-gradle-user-home")

intellijPlatformTesting {
    runIde {
        register("runIdeForMcpE2e") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    val toolkit = mcpE2eAwtToolkit.get().takeIf { it.isNotBlank() }
                    listOfNotNull(
                        "-Didea.mcp.server.force.enable=true",
                        "-Didea.mcp.server.force.port=${mcpE2ePort.get()}",
                        "-Didea.trust.all.projects=true",
                        "-Dgradle.user.home=${mcpE2eGradleUserHome.get().asFile.absolutePath}",
                        toolkit?.let { "-Dawt.toolkit.name=$it" },
                    )
                }
                environment("GRADLE_USER_HOME", mcpE2eGradleUserHome.get().asFile.absolutePath)
                argumentProviders += CommandLineArgumentProvider {
                    val opened = when (mcpE2eFixtureKind.get()) {
                        "gradle" -> mcpE2eGradleFixture
                        "maven" -> mcpE2eMavenFixture
                        else -> mcpE2eFixture
                    }
                    listOf(opened.get().asFile.absolutePath)
                }
                doFirst {
                    // A fresh copy on every start, so that edits of an earlier run (e.g. mc_safe_delete apply) are
                    // gone. The sibling directory is a second content root outside the project directory.
                    val fixture = mcpE2eFixture.get().asFile
                    val sibling = mcpE2eSibling.get().asFile
                    listOf(fixture, sibling).forEach { it.deleteRecursively() }
                    mcpE2eFixtureSource.asFile.resolve("project").copyRecursively(fixture)
                    mcpE2eFixtureSource.asFile.resolve("sibling").copyRecursively(sibling)
                    fixture.resolve("README.md").writeText("Throwaway project opened by runIdeForMcpE2e.\n")
                    // JUnit 4 for the test fixtures (mc_run tests), taken from the sandbox IDE's own JUnit plugin, so
                    // nothing is downloaded: the JPS fixture's library junit4 and the Gradle fixture's file
                    // dependencies point to these copies.
                    val junitDirectory = platformPath.resolve("plugins/junit/lib").toFile()
                    val junitJars = listOf(
                        "intellij.libraries.junit4.jar" to "junit-4.13.2.jar",
                        "intellij.libraries.hamcrest.jar" to "hamcrest.jar",
                    )
                    junitJars.forEach { (jar, copy) ->
                        junitDirectory.resolve(jar).copyTo(fixture.resolve("lib/$copy"), overwrite = true)
                    }
                    if (mcpE2eFixtureKind.get() == "maven") {
                        val mavenFixture = mcpE2eMavenFixture.get().asFile
                        val repository = mcpE2eMavenRepository.get().asFile.apply { mkdirs() }
                        mavenFixture.deleteRecursively()
                        sandboxSystemDirectory.get().asFile.resolve("projects").listFiles().orEmpty()
                            .filter { it.name.startsWith("${mavenFixture.name}.") }.forEach { it.deleteRecursively() }
                        mcpE2eMavenFixtureSource.asFile.copyRecursively(mavenFixture)
                        val settings = mavenFixture.resolve(".idea/mcp-e2e-maven-settings.xml")
                        // The source has no .idea directory (git ignores it there).
                        settings.parentFile.mkdirs()
                        settings.writeText(
                            """
                            |<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
                            |  <localRepository>${repository.absolutePath}</localRepository>
                            |</settings>
                            |
                            """.trimMargin(),
                        )
                        // Machine-specific, so written here: the private repository and these settings, never ~/.m2.
                        mavenFixture.resolve(".idea/workspace.xml").writeText(
                            """
                            |<?xml version="1.0" encoding="UTF-8"?>
                            |<project version="4">
                            |  <component name="MavenImportPreferences">
                            |    <option name="generalSettings">
                            |      <MavenGeneralSettings>
                            |        <option name="localRepository" value="${repository.absolutePath}" />
                            |        <option name="userSettingsFile" value="${settings.absolutePath}" />
                            |      </MavenGeneralSettings>
                            |    </option>
                            |  </component>
                            |</project>
                            |
                            """.trimMargin(),
                        )
                    }
                    if (mcpE2eFixtureKind.get() == "gradle") {
                        val gradleFixture = mcpE2eGradleFixture.get().asFile
                        val gradleHome = mcpE2eGradleHome.get()
                        check(File(gradleHome, "bin/gradle").isFile) { "no unpacked Gradle distribution: $gradleHome" }
                        gradleFixture.deleteRecursively()
                        mcpE2eGradleSibling.get().asFile.let {
                            it.deleteRecursively()
                            mcpE2eGradleSiblingSource.asFile.copyRecursively(it)
                        }
                        // Forget the sandbox's cached model of an earlier run, so the IDE imports the project afresh.
                        sandboxSystemDirectory.get().asFile.resolve("projects").listFiles().orEmpty()
                            .filter { it.name.startsWith("${gradleFixture.name}.") }.forEach { it.deleteRecursively() }
                        mcpE2eGradleFixtureSource.asFile.copyRecursively(gradleFixture)
                        junitJars.forEach { (jar, copy) ->
                            val target = gradleFixture.resolve("testlibs/$copy")
                            junitDirectory.resolve(jar).copyTo(target, overwrite = true)
                        }
                        // Machine-specific, so written here: the local Gradle; Gradle runs on JAVA_HOME of the caller
                        // (Gradle sync refused the sandbox JDK entry "mcp-e2e-jdk" as its JVM).
                        gradleFixture.resolve(".idea/gradle.xml").writeText(
                            """
                            |<?xml version="1.0" encoding="UTF-8"?>
                            |<project version="4">
                            |  <component name="GradleSettings">
                            |    <option name="linkedExternalProjectsSettings">
                            |      <GradleProjectSettings>
                            |        <option name="distributionType" value="LOCAL" />
                            |        <option name="externalProjectPath" value="${'$'}PROJECT_DIR${'$'}" />
                            |        <option name="gradleHome" value="$gradleHome" />
                            |        <option name="gradleJvm" value="#JAVA_HOME" />
                            |        <option name="delegatedBuild" value="true" />
                            |        <option name="testRunner" value="GRADLE" />
                            |      </GradleProjectSettings>
                            |    </option>
                            |  </component>
                            |</project>
                            |
                            """.trimMargin(),
                        )
                    }
                    // The fixture's project JDK "mcp-e2e-jdk" (see its .idea/misc.xml) is the runtime the sandbox IDE
                    // runs on; it has javac and JDWP. It is defined in the sandbox's own JDK table, never the owner's:
                    // a Java 9+ JDK with one jrt root per module, as the IDE itself would write it.
                    val home = runtimeDirectory.get().asFile
                    val release = home.resolve("release").readLines()
                    fun releaseValue(key: String) =
                        release.first { it.startsWith("$key=") }.substringAfter('=').trim('"')
                    val modules = releaseValue("MODULES").split(' ').filter { it.isNotBlank() }
                    // Without a version the IDE cannot tell which Java this is, and Gradle refuses it as its JVM.
                    val version = "JetBrains Runtime version ${releaseValue("JAVA_VERSION")}"
                    val roots = modules.joinToString("\n") {
                        "            <root url=\"jrt://${home.absolutePath}!/$it\" type=\"simple\" />"
                    }
                    val table = sandboxConfigDirectory.get().asFile.resolve("options/jdk.table.xml")
                    table.parentFile.mkdirs()
                    table.writeText(
                        """
                        |<application>
                        |  <component name="ProjectJdkTable">
                        |    <jdk version="2">
                        |      <name value="mcp-e2e-jdk" />
                        |      <type value="JavaSDK" />
                        |      <version value="$version" />
                        |      <homePath value="${home.absolutePath}" />
                        |      <roots>
                        |        <annotationsPath><root type="composite" /></annotationsPath>
                        |        <classPath>
                        |          <root type="composite">
                        |$roots
                        |          </root>
                        |        </classPath>
                        |        <javadocPath><root type="composite" /></javadocPath>
                        |        <sourcePath><root type="composite" /></sourcePath>
                        |      </roots>
                        |      <additional />
                        |    </jdk>
                        |  </component>
                        |</application>
                        |
                        """.trimMargin(),
                    )
                }
            }
        }
    }
}
