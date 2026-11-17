package com.github.sinlordep.mcpcompanion.yield

import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class McpToolNameReaderTest {

    open class BaseTools {
        @McpTool(name = "inherited_tool")
        @McpDescription(description = "d")
        open suspend fun inherited(): String = "x"
    }

    class SampleTools : BaseTools() {
        @McpTool(name = "named_tool")
        @McpDescription(description = "d")
        suspend fun named(): String = "x"

        @McpTool
        @McpDescription(description = "d")
        suspend fun fallback_name(): String = "x"

        override suspend fun inherited(): String = "y"

        fun notATool(): String = "z"
    }

    class Broken

    @Test
    fun `mirrors the server rules - explicit name, blank-name fallback, annotation on the overridden function`() {
        assertEquals(
            setOf("named_tool", "fallback_name", "inherited_tool"),
            McpToolNameReader().toolNamesOf(SampleTools::class.java),
        )
    }

    @Test
    fun `a class that cannot be reflected contributes no names and does not affect other classes`() {
        for (failure in listOf(NoClassDefFoundError("x/Missing"), UnsupportedOperationException(), LinkageError())) {
            val reader = McpToolNameReader { type -> if (type == Broken::class.java) throw failure else setOf("ok") }
            assertTrue(reader.toolNamesOf(Broken::class.java).isEmpty())
            assertEquals(setOf("ok"), reader.toolNamesOf(SampleTools::class.java))
        }
    }

    @Test
    fun `a failed class is memoised, so it is reflected and logged only once`() {
        var attempts = 0
        val reader = McpToolNameReader {
            attempts++
            throw NoClassDefFoundError("x/Missing")
        }
        repeat(3) { reader.toolNamesOf(Broken::class.java) }
        assertEquals(1, attempts)
    }

    @Test
    fun `cancellation is never swallowed and not memoised`() {
        for (cancellation in listOf(ProcessCanceledException(), CancellationException("cancelled"))) {
            var attempts = 0
            val reader = McpToolNameReader {
                attempts++
                throw cancellation
            }
            assertThrows(cancellation.javaClass) { reader.toolNamesOf(Broken::class.java) }
            assertThrows(cancellation.javaClass) { reader.toolNamesOf(Broken::class.java) }
            assertEquals(2, attempts)
        }
    }
}
