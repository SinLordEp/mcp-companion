package mcpcompanion.e2e

import java.io.File

/**
 * Machine-specific project files of the Gradle fixture, written on every start: the local, already unpacked Gradle
 * distribution (never a download), delegated build and test runs. Gradle runs on `JAVA_HOME` of the caller, because
 * Gradle sync refused the sandbox JDK entry as its JVM.
 */
object GradleFixtureFiles {
    fun gradleSettings(gradleHome: String): String = """
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
    """.trimMargin()

    /** The newest Gradle distribution a wrapper unpacked under [gradleUserHome] (`wrapper/dists/...`), or null. */
    fun newestUnpackedDistribution(gradleUserHome: File): File? =
        gradleUserHome.resolve("wrapper/dists").listFiles().orEmpty()
            .filter { it.name.matches(Regex("gradle-[0-9.]+-bin")) }
            .flatMap { dist -> dist.listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() } }
            .filter { it.resolve("bin/gradle").isFile }
            .maxByOrNull { versionKey(it.name.removePrefix("gradle-")) }

    /** `9.10` sorts after `9.8.1`: major, minor and patch, a missing part counting as 0. */
    private fun versionKey(version: String): Long {
        val parts = version.split('.').map(String::toLong)
        return (0 until VERSION_PARTS).fold(0L) { key, index ->
            key * VERSION_PART_RANGE + parts.getOrElse(index) { 0L }
        }
    }

    private const val VERSION_PARTS = 3
    private const val VERSION_PART_RANGE = 1000L
}

/**
 * Machine-specific project files of the Maven fixture: Maven settings and import preferences that point to a private
 * local repository under `build/`, so that nothing is read from or written to `~/.m2`. With [delegatedBuild] the IDE
 * hands building to Maven ("Delegate IDE build/run actions to Maven"), otherwise it builds the imported modules itself.
 */
object MavenFixtureFiles {
    fun mavenSettings(repository: File): String = """
        |<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
        |  <localRepository>${repository.absolutePath}</localRepository>
        |</settings>
        |
    """.trimMargin()

    fun workspace(repository: File, settings: File, delegatedBuild: Boolean = false): String = """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<project version="4">
        |  <component name="MavenRunner">
        |    <option name="delegateBuildToMaven" value="$delegatedBuild" />
        |  </component>
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
    """.trimMargin()
}
