package mcpcompanion.e2e

import java.io.File

/**
 * The MCP Server's brave mode in the sandbox's `options/mcpServer.xml`. Only this one option is set; the rest of the
 * sandbox's MCP Server settings stays as it is.
 */
object BraveModeOption {
    private const val COMPONENT = "McpServerSettings"
    private val storedOption = Regex("<option name=\"enableBraveMode\"[^>]*/>")
    private val component = Regex("<component name=\"$COMPONENT\"\\s*(/?)>")

    fun write(options: File, braveMode: Boolean) {
        options.parentFile.mkdirs()
        options.writeText(apply(options.takeIf { it.isFile }?.readText().orEmpty(), braveMode))
    }

    /** [stored] with brave mode set to [braveMode], whatever shape the stored file had. */
    fun apply(stored: String, braveMode: Boolean): String {
        val option = "<option name=\"enableBraveMode\" value=\"$braveMode\" />"
        val newComponent = "<component name=\"$COMPONENT\">\n    $option\n  </component>"
        val found = component.find(stored)
        return when {
            storedOption.containsMatchIn(stored) -> stored.replace(storedOption, option)
            found != null && found.groupValues[1] == "/" -> stored.replaceRange(found.range, newComponent)
            found != null -> stored.replaceRange(found.range, "${found.value}\n    $option")
            "</application>" in stored -> stored.replace("</application>", "  $newComponent\n</application>")
            else -> "<application>\n  $newComponent\n</application>\n"
        }
    }
}

/**
 * The fixture's project JDK `mcp-e2e-jdk` (named in its `.idea/misc.xml`) in the sandbox's own JDK table, never the
 * owner's: the runtime the sandbox IDE runs on, which has javac and JDWP. A Java 9+ JDK with one `jrt` root per module,
 * as the IDE itself writes it.
 */
object SandboxJdkTable {
    const val JDK_NAME = "mcp-e2e-jdk"

    fun write(table: File, home: File) {
        table.parentFile.mkdirs()
        table.writeText(render(home))
    }

    fun render(home: File): String {
        val release = home.resolve("release").readLines()
        fun releaseValue(key: String) = release.first { it.startsWith("$key=") }.substringAfter('=').trim('"')
        val modules = releaseValue("MODULES").split(' ').filter { it.isNotBlank() }
        // Without a version the IDE cannot tell which Java this is, and Gradle refuses it as its JVM.
        val version = "JetBrains Runtime version ${releaseValue("JAVA_VERSION")}"
        val roots = modules.joinToString("\n") {
            "            <root url=\"jrt://${home.absolutePath}!/$it\" type=\"simple\" />"
        }
        return """
            |<application>
            |  <component name="ProjectJdkTable">
            |    <jdk version="2">
            |      <name value="$JDK_NAME" />
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
        """.trimMargin()
    }
}
