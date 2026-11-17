package com.github.sinlordep.mcpcompanion.common.paths

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathSyntaxTest {

    private fun normalised(path: String) = PathSyntax.parse(path).toString()

    @Test
    fun `absolute forms are recognised on any OS`() {
        for (path in listOf("/a", "C:\\a", "c:/a", "C:", "\\\\server\\share\\a", "//server/share")) {
            assertTrue(path, PathSyntax.parse(path).isAbsolute)
        }
        for (path in listOf("a/b", "./a", "../a", "a\\b", "")) {
            assertFalse(path, PathSyntax.parse(path).isAbsolute)
        }
    }

    @Test
    fun `normalises separators, dots and repeated slashes`() {
        assertEquals("/a/c", normalised("/a/./b/../c/"))
        assertEquals("/a/b", normalised("/a//b"))
        assertEquals("C:/a/b", normalised("c:\\a\\\\b"))
        assertEquals("//server/share/a", normalised("\\\\server\\share\\a\\"))
    }

    @Test
    fun `dot-dot stops at the absolute prefix but is kept in relative paths`() {
        assertEquals("/", normalised("/.."))
        assertEquals("C:/", normalised("C:/a/../.."))
        assertEquals("//server/share", normalised("//server/share/.."))
        assertEquals("../../a", normalised("../x/../../a"))
        assertEquals(".", normalised("a/.."))
    }
}
