package com.github.sinlordep.mcpcompanion.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentGuideTest {

    private val guide = AgentGuide().text()

    @Test
    fun `the bundled guide is a short Markdown document`() {
        assertTrue(guide.startsWith("# "))
        assertTrue("ends with a newline", guide.endsWith("\n"))
        val lines = guide.lines().size
        assertTrue("$lines lines", lines in 30..50)
    }

    @Test
    fun `a missing resource is an error, not an empty guide`() {
        assertThrows(IllegalStateException::class.java) { AgentGuide("/agent/no-such-guide.md").text() }
    }

    /** The repository's Claude skill is the guide with frontmatter; edit the resource and copy it over. */
    @Test
    fun `the Claude skill in the repository carries exactly the bundled guide`() {
        // The test task's working directory is the project directory; build.gradle.kts declares skills/ as input.
        val skill = File(SKILL).readText(Charsets.UTF_8)
        val frontmatter = Regex("\\A---\\n(.*?)\\n---\\n\\n", RegexOption.DOT_MATCHES_ALL).find(skill)
            ?: error("$SKILL has no frontmatter followed by an empty line")
        val fields = frontmatter.groupValues[1].lines()
        assertTrue(fields.toString(), "name: mcp-companion" in fields)
        assertTrue(fields.toString(), fields.any { it.startsWith("description: ") && "mc_" in it })
        assertEquals("body of $SKILL", guide, skill.substring(frontmatter.range.last + 1))
    }

    private companion object {
        const val SKILL = "skills/mcp-companion/SKILL.md"
    }
}
