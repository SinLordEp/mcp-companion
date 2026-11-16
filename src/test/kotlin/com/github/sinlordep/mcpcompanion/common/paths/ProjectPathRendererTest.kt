package com.github.sinlordep.mcpcompanion.common.paths

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProjectPathRendererTest {

    private val unix = ProjectPathRenderer("/home/dev/project/", caseSensitive = true)
    private val windows = ProjectPathRenderer("C:\\work\\Project", caseSensitive = false)
    private val unc = ProjectPathRenderer("\\\\server\\share\\project", caseSensitive = false)

    // ---- Unix ----

    @Test
    fun `unix - renders files under the root relative to it`() {
        assertEquals("src/Foo.java", unix.render("/home/dev/project/src/Foo.java"))
        assertEquals(".", unix.render("/home/dev/project"))
    }

    @Test
    fun `unix - files outside the root stay absolute, including look-alike prefixes`() {
        assertEquals("/home/dev/other/Foo.java", unix.render("/home/dev/other/Foo.java"))
        assertEquals("/home/dev/project2/Foo.java", unix.render("/home/dev/project2/Foo.java"))
    }

    @Test
    fun `unix - the root prefix is case-sensitive on a case-sensitive file system`() {
        assertNull(unix.relativize("/home/dev/PROJECT/Foo.java"))
    }

    @Test
    fun `unix - client paths resolve against the root, absolute ones stay`() {
        assertEquals("/home/dev/project/src/Foo.java", unix.resolve("src/Foo.java"))
        assertEquals("/home/dev/project/src/Foo.java", unix.resolve("./src/./Foo.java"))
        assertEquals("/elsewhere/Foo.java", unix.resolve("/elsewhere/Foo.java"))
    }

    @Test
    fun `unix - dot-dot segments are applied before deciding inside or outside`() {
        assertEquals("/home/dev/outside/File.java", unix.resolve("../outside/File.java"))
        assertNull(unix.relativize(unix.resolve("../outside/File.java")))
        assertEquals("/home/dev/x", unix.render("/home/dev/project/../x"))
        assertEquals("src/Foo.java", unix.render("/home/dev/project/lib/../src/Foo.java"))
        assertEquals("/x", unix.resolve("../../../../../x"))
    }

    // ---- Windows drive letters ----

    @Test
    fun `windows - backslashes, either separator and drive-letter case are accepted`() {
        assertEquals("src/Foo.java", windows.render("C:\\work\\Project\\src\\Foo.java"))
        assertEquals("src/Foo.java", windows.render("c:/work/Project/src/Foo.java"))
        assertEquals("src/Foo.java", windows.relativize("C:/WORK/project/src/Foo.java"))
    }

    @Test
    fun `windows - an absolute client path outside the root is not treated as relative`() {
        assertEquals("C:/other/Foo.java", windows.resolve("C:/other/Foo.java"))
        assertEquals("D:/proj/src/Foo.java", windows.resolve("d:\\proj\\src\\Foo.java"))
        assertNull(windows.relativize(windows.resolve("C:\\proj\\src\\Foo.java")))
        assertEquals("C:/work/Project/src/Foo.java", windows.resolve("src\\Foo.java"))
    }

    @Test
    fun `windows - dot-dot cannot escape detection or climb above the drive`() {
        assertNull(windows.relativize(windows.resolve("..\\outside\\File.java")))
        assertEquals("C:/x", windows.resolve("C:\\..\\..\\x"))
    }

    // ---- UNC ----

    @Test
    fun `unc - share paths are absolute and relative to a share root`() {
        assertEquals("src/Foo.java", unc.render("\\\\server\\share\\project\\src\\Foo.java"))
        assertEquals("src/Foo.java", unc.render("//SERVER/share/project/src/Foo.java"))
        assertEquals("//server/other/Foo.java", unc.resolve("\\\\server\\other\\Foo.java"))
        assertNull(unc.relativize("//server/other/Foo.java"))
    }

    @Test
    fun `unc - dot-dot never leaves the share`() {
        assertEquals("//server/share/x", unc.resolve("../../../x"))
        assertNull(unc.relativize(unc.resolve("../x")))
    }

    @Test
    fun `the root must be absolute`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPathRenderer("relative/root", caseSensitive = true)
        }
    }
}
