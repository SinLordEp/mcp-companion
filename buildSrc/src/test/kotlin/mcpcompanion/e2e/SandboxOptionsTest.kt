package mcpcompanion.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SandboxOptionsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val on = "<option name=\"enableBraveMode\" value=\"true\" />"

    @Test
    fun `brave mode is added to an empty file`() {
        assertEquals(
            "<application>\n  <component name=\"McpServerSettings\">\n    $on\n  </component>\n</application>\n",
            BraveModeOption.apply("", braveMode = true),
        )
    }

    @Test
    fun `stored brave mode is replaced and nothing else changes`() {
        val stored = "<application>\n  <component name=\"McpServerSettings\">\n    " +
            "<option name=\"enableBraveMode\" value=\"false\" />\n    <option name=\"other\" value=\"1\" />\n" +
            "  </component>\n</application>\n"
        assertEquals(stored.replace("value=\"false\"", "value=\"true\""), BraveModeOption.apply(stored, true))
    }

    @Test
    fun `self-closing component gets the option`() {
        assertEquals(
            "<application>\n  <component name=\"McpServerSettings\">\n    $on\n  </component>\n</application>",
            BraveModeOption.apply("<application>\n  <component name=\"McpServerSettings\" />\n</application>", true),
        )
    }

    @Test
    fun `component without the option gets it first`() {
        val stored = "<application><component name=\"McpServerSettings\"><option name=\"x\" /></component></application>"
        assertEquals(
            "<application><component name=\"McpServerSettings\">\n    $on<option name=\"x\" /></component></application>",
            BraveModeOption.apply(stored, true),
        )
    }

    @Test
    fun `other components are kept`() {
        val stored = "<application>\n  <component name=\"Other\" />\n</application>"
        assertEquals(
            "<application>\n  <component name=\"Other\" />\n" +
                "  <component name=\"McpServerSettings\">\n    $on\n  </component>\n</application>",
            BraveModeOption.apply(stored, true),
        )
    }

    @Test
    fun `jdk table has one jrt root per module and the runtime version`() {
        val home = temporary.newFolder("jbr")
        home.resolve("release").writeText("JAVA_VERSION=\"25.0.4\"\nMODULES=\"java.base java.logging\"\n")
        val table = SandboxJdkTable.render(home)
        assertEquals(2, Regex("<root url=\"jrt://").findAll(table).count())
        assertEquals(true, "JetBrains Runtime version 25.0.4" in table)
        assertEquals(true, "jrt://${home.absolutePath}!/java.logging" in table)
    }

    @Test
    fun `newest unpacked gradle distribution wins by version, not by name`() {
        val userHome = temporary.newFolder("gradle-home")
        listOf("9.8.1", "9.10").forEach { version ->
            val bin = userHome.resolve("wrapper/dists/gradle-$version-bin/hash/gradle-$version/bin")
            bin.mkdirs()
            bin.resolve("gradle").writeText("")
        }
        userHome.resolve("wrapper/dists/gradle-9.11-bin/hash/gradle-9.11").mkdirs()
        assertEquals("gradle-9.10", GradleFixtureFiles.newestUnpackedDistribution(userHome)?.name)
        assertNull(GradleFixtureFiles.newestUnpackedDistribution(temporary.newFolder("empty")))
    }

    @Test
    fun `fixture and confirmation values are strict`() {
        assertEquals(McpE2eFixture.MAVEN, McpE2eFixture.parse("maven"))
        assertEquals(true, LaunchConfirmation.parse("skip").braveMode)
        assertEquals(
            "mcpE2eFixture must be one of jps, gradle, maven, was ant",
            runCatching { McpE2eFixture.parse("ant") }.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `the maven workspace delegates building only when asked`() {
        val repository = java.io.File("/w/m2")
        val settings = java.io.File("/w/settings.xml")
        val delegated = "<option name=\"delegateBuildToMaven\" value=\"true\" />"
        assertEquals(true, delegated in MavenFixtureFiles.workspace(repository, settings, delegatedBuild = true))
        assertEquals(false, delegated in MavenFixtureFiles.workspace(repository, settings))
    }
}
