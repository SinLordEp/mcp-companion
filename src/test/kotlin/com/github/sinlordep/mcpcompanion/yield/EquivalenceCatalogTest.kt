package com.github.sinlordep.mcpcompanion.yield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EquivalenceCatalogTest {

    @Test
    fun `parses one toolset per line and ignores comments and blanks`() {
        val catalog = EquivalenceCatalog.parse(
            """
            # header comment
            navigation = find_usages, find_implementations   # trailing comment

            run=execute_run_configuration
            empty =
            """.trimIndent(),
        )
        assertEquals(setOf("find_usages", "find_implementations"), catalog.equivalentsOf("navigation"))
        assertEquals(setOf("execute_run_configuration"), catalog.equivalentsOf("run"))
        assertTrue(catalog.equivalentsOf("empty").isEmpty())
        assertTrue(catalog.equivalentsOf("unknown").isEmpty())
    }

    @Test
    fun `rejects a line without a toolset id`() {
        assertThrows(IllegalArgumentException::class.java) { EquivalenceCatalog.parse("find_usages") }
        assertThrows(IllegalArgumentException::class.java) { EquivalenceCatalog.parse("= find_usages") }
    }

    @Test
    fun `rejects a toolset listed twice`() {
        assertThrows(IllegalArgumentException::class.java) { EquivalenceCatalog.parse("a = x\na = y") }
    }

    @Test
    fun `the shipped catalog parses`() {
        // Loads src/main/resources/mcp-companion/official-equivalents.txt; no shipped toolset has an equivalent yet.
        assertTrue(EquivalenceCatalog.bundled().equivalentsOf("status").isEmpty())
    }
}
