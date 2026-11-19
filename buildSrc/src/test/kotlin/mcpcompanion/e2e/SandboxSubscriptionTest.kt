package mcpcompanion.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SandboxSubscriptionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `an absolute path to an existing file is the key`() {
        val key = temporary.newFile("idea.key")
        assertEquals(key, SandboxSubscription.keyFile(key.absolutePath))
        assertEquals(key, SandboxSubscription.keyFile("  ${key.absolutePath}\n"))
    }

    @Test
    fun `an empty value means no key`() {
        assertNull(SandboxSubscription.keyFile(""))
        assertNull(SandboxSubscription.keyFile("   "))
    }

    @Test
    fun `a missing file, a directory or a relative path is no key`() {
        assertNull(SandboxSubscription.keyFile(temporary.root.resolve("missing.key").absolutePath))
        assertNull(SandboxSubscription.keyFile(temporary.newFolder("dir").absolutePath))
        assertNull(SandboxSubscription.keyFile("idea.key"))
    }
}
