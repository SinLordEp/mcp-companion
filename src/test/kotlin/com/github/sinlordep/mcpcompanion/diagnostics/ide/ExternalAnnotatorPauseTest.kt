package com.github.sinlordep.mcpcompanion.diagnostics.ide

import com.intellij.testFramework.LightVirtualFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** External annotators are paused exactly while some analysis of the file runs, also with overlapping analyses. */
class ExternalAnnotatorPauseTest {

    private val pause = ExternalAnnotatorPause()
    private val a = LightVirtualFile("A.java")
    private val b = LightVirtualFile("B.java")

    @Test
    fun `paused during the analysis of the file only`() = runBlocking {
        assertFalse(pause.isPaused(a))
        pause.during(listOf(a)) {
            assertTrue(pause.isPaused(a))
            assertFalse(pause.isPaused(b))
        }
        assertFalse(pause.isPaused(a))
    }

    @Test
    fun `overlapping analyses keep the pause until the last one ends, also when one fails`() = runBlocking {
        pause.during(listOf(a)) {
            runCatching { pause.during(listOf(a, b)) { error("analysis failed") } }
            assertTrue("the outer analysis still runs", pause.isPaused(a))
            assertFalse(pause.isPaused(b))
        }
        assertFalse(pause.isPaused(a))
    }
}
