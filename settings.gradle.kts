import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "mcp-companion"

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.4.21"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
        id("org.jetbrains.changelog") version "2.5.0"
        // detekt 2.x: 1.23.8 (latest 1.x) embeds Kotlin 2.0.21; 2.0.0-alpha.6 embeds Kotlin 2.4.10, matching 2.4.21.
        id("dev.detekt") version "2.0.0-alpha.6"
        id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.20.1"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    // Configure all projects' repositories
    repositories {
        mavenCentral()

        // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
        intellijPlatform {
            defaultRepositories()
        }
    }
}
