package com.github.sinlordep.mcpcompanion.sync.externalsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a failed external-system sync is described; the samples are what Gradle sent end to end. */
class FailureTextTest {

    private val gradleOutput = """
        FAILURE: Build failed with an exception.
        * Where:
        Build file '/p/lib/build.gradle' line: 5
        * What went wrong:
        Could not compile build file '/p/lib/build.gradle'.
        startup failed:
          build file '/p/lib/build.gradle': 5: Unexpected input: '{' @ line 5, column 14.
    """.trimIndent()

    private val wrapperTrace = """
        com.intellij.openapi.externalSystem.service.internal.ExternalSystemPartialResolutionException:
        	at org.jetbrains.plugins.gradle.service.project.GradleProjectResolver.resolveProjectInfo(GradleProjectResolver.java:217)
        	... 12 more
    """.trimIndent()

    @Test
    fun `a wrapper exception gives way to what Gradle says went wrong`() {
        val text = FailureText()
        text.exception(IllegalStateException("com.example.WrapperException:"))
        text.output(gradleOutput)
        val failed = text.describe(wrapperTrace, wrapperTrace)
        assertEquals("Could not compile build file '/p/lib/build.gradle'.", failed.message)
        assertTrue(failed.details!!, "Unexpected input: '{' @ line 5" in failed.details)
        assertTrue(failed.details, "\tat " !in failed.details)
    }

    @Test
    fun `the exception chain's messages come first, causes and suppressed ones included`() {
        val root = RuntimeException("Could not resolve com.example:missing:1.0")
        val top = IllegalStateException("Sync failed", root).apply { addSuppressed(RuntimeException("second reason")) }
        val text = FailureText()
        text.exception(top)
        val failed = text.describe(wrapperTrace, null)
        assertEquals("Sync failed", failed.message)
        assertEquals("Could not resolve com.example:missing:1.0\nsecond reason", failed.details)
    }

    @Test
    fun `without events the callback text is used without stack frames`() {
        val failed = FailureText().describe(wrapperTrace, wrapperTrace)
        assertEquals(
            "com.intellij.openapi.externalSystem.service.internal.ExternalSystemPartialResolutionException:",
            failed.message,
        )
        assertEquals(failed.message, failed.details)
    }

    @Test
    fun `error output is bounded to its last lines`() {
        val text = FailureText()
        text.output((1..100).joinToString("\n") { "line $it" })
        val details = text.describe("x", null).details!!.lines()
        assertEquals(40, details.size)
        assertEquals("line 100", details.last())
    }
}
