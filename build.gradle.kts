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
val mcpE2eFixture = layout.buildDirectory.dir("mcp-e2e-fixture")
val mcpE2eSibling = layout.buildDirectory.dir("mcp-e2e-sibling")
val mcpE2eFixtureSource = layout.projectDirectory.dir("src/mcpE2eFixture")

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
                        toolkit?.let { "-Dawt.toolkit.name=$it" },
                    )
                }
                argumentProviders += CommandLineArgumentProvider { listOf(mcpE2eFixture.get().asFile.absolutePath) }
                doFirst {
                    // A fresh copy on every start, so that edits of an earlier run (e.g. mc_safe_delete apply) are
                    // gone. The sibling directory is a second content root outside the project directory.
                    val fixture = mcpE2eFixture.get().asFile
                    val sibling = mcpE2eSibling.get().asFile
                    listOf(fixture, sibling).forEach { it.deleteRecursively() }
                    mcpE2eFixtureSource.asFile.resolve("project").copyRecursively(fixture)
                    mcpE2eFixtureSource.asFile.resolve("sibling").copyRecursively(sibling)
                    fixture.resolve("README.md").writeText("Throwaway project opened by runIdeForMcpE2e.\n")
                }
            }
        }
    }
}
