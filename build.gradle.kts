import mcpcompanion.e2e.McpE2eJvmArguments
import mcpcompanion.e2e.McpE2eLayout
import mcpcompanion.e2e.McpE2eOpenedProject
import mcpcompanion.e2e.PrepareMcpE2eSandbox
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

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

// Only the latest major IDE version is supported; see docs/PLAN.md section 3.
val supportedMajorBuild = 263

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

// ---- Plugin descriptor, verification, signing and publishing ------------------------------------------------------
// The description is maintained in plugin.xml only; the change notes come from CHANGELOG.md through the Gradle
// Changelog Plugin (the section of the current version, or [Unreleased] before patchChangelog). See docs/RELEASING.md.

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "$supportedMajorBuild"
            // No upper bound: later IDE versions stay compatible until an API change needs a new plugin version.
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            // The IDE the plugin is built against (IntelliJ IDEA 2026.3).
            current()
            // The newest EAP of the next major version, once JetBrains publishes one; without one nothing is added.
            latest {
                types = listOf(IntelliJPlatformType.IntellijIdea)
                channels = listOf(ProductRelease.Channel.EAP)
                sinceBuild = "${supportedMajorBuild + 1}"
            }
        }
    }
    // Secrets only from the environment (GitHub Actions secrets in .github/workflows/release.yml), never from files in
    // the repository. Key and chain as PEM text: signPlugin also accepts them Base64-encoded, verifyPluginSignature
    // (which publishPlugin runs) does not (IntelliJ Platform Gradle Plugin 2.20.1, docs/RELEASING.md).
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // A pre-release version such as 1.2.0-beta.1 goes to the Marketplace channel "beta"; 1.2.0 goes to "default".
        channels = listOf(version.toString().substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
    }
}

// verifyPluginSignature reads signPlugin's output but does not depend on it (IntelliJ Platform Gradle Plugin 2.20.1).
// Publishing checks the signature before anything is uploaded.
tasks.verifyPluginSignature {
    dependsOn(tasks.signPlugin)
}
tasks.publishPlugin {
    dependsOn(tasks.verifyPluginSignature)
}

changelog {
    // patchChangelog starts a new [Unreleased] section without empty group headings.
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    // Release tags are the bare version (0.1.0), as the release draft in .github/workflows/build.yml creates them.
    versionPrefix = ""
}

// ---- Quality gates (all part of `./gradlew check`) ---------------------------------------------------------------

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
    source.setFrom(
        files("src/main/kotlin", "src/test/kotlin", "src/architectureTest/kotlin", "buildSrc/src/main/kotlin"),
    )
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
//   ./gradlew runIdeForMcpE2e [-PmcpE2ePort=64399] [-PmcpE2eAwtToolkit=XToolkit] [-PmcpE2eFixture=jps|gradle|maven]
//                             [-PmcpE2eLaunchConfirmation=skip|ask] [-PmcpE2eGradleHome=<unpacked Gradle>]
// The bundled MCP server is force-enabled on a fixed loopback port and the sandbox opens only a throwaway copy of a
// fixture under build/. Endpoints: http://127.0.0.1:<port>/sse (SSE) and http://127.0.0.1:<port>/stream (Streamable
// HTTP). The logic lives in buildSrc/src/main/kotlin/mcpcompanion/e2e; see README.md, Development.

val mcpE2eGradleUserHome = layout.buildDirectory.dir(McpE2eLayout.GRADLE_USER_HOME).map { it.asFile.absolutePath }

val prepareMcpE2eSandbox = tasks.register<PrepareMcpE2eSandbox>("prepareMcpE2eSandbox") {
    description = "Copies a fresh e2e fixture and writes the sandbox options; run by runIdeForMcpE2e."
    fixture = providers.gradleProperty("mcpE2eFixture").orElse("jps")
    launchConfirmation = providers.gradleProperty("mcpE2eLaunchConfirmation").orElse("skip")
    gradleHome = providers.gradleProperty("mcpE2eGradleHome")
    gradleUserHome = gradle.gradleUserHomeDir
    fixtureSources = layout.projectDirectory.dir("src")
    workDirectory = layout.buildDirectory
    // Installation, runtime and sandbox directories of the sandbox IDE, as its run task resolves them.
    val ide = tasks.named<RunIdeTask>("runIdeForMcpE2e")
    platformDirectory.fileProvider(ide.map { it.platformPath.toFile() })
    runtimeDirectory = ide.flatMap { it.runtimeDirectory }
    sandboxConfigDirectory = ide.flatMap { it.sandboxConfigDirectory }
    sandboxSystemDirectory = ide.flatMap { it.sandboxSystemDirectory }
}

intellijPlatformTesting {
    runIde {
        register("runIdeForMcpE2e") {
            task {
                dependsOn(prepareMcpE2eSandbox)
                jvmArgumentProviders += McpE2eJvmArguments(
                    port = providers.gradleProperty("mcpE2ePort").orElse("64399"),
                    awtToolkit = providers.gradleProperty("mcpE2eAwtToolkit").orElse(""),
                    gradleUserHome = mcpE2eGradleUserHome,
                )
                environment("GRADLE_USER_HOME", mcpE2eGradleUserHome.get())
                argumentProviders += McpE2eOpenedProject(
                    prepareMcpE2eSandbox.flatMap { it.openedProject }.map { it.asFile.absolutePath },
                )
            }
        }
    }
}
