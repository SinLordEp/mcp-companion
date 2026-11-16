package com.github.sinlordep.mcpcompanion.common.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ToolResultTest {

    private val failure = ToolResult.failure(ErrorCode.NOT_FOUND, "missing", "look elsewhere")

    @Test
    fun `map transforms a success`() {
        assertEquals(ToolResult.Success(4), ToolResult.success(2).map { it * 2 })
    }

    @Test
    fun `map keeps a failure untouched`() {
        assertSame(failure, failure.map { error("must not be called") })
    }

    @Test
    fun `flatMap chains successes and short-circuits on failure`() {
        assertEquals(ToolResult.Success("2"), ToolResult.success(2).flatMap { ToolResult.success(it.toString()) })
        assertSame(failure, ToolResult.success(2).flatMap { failure })
        assertSame(failure, failure.flatMap { ToolResult.success(it) })
    }

    @Test
    fun `failure carries code, message and next step`() {
        assertEquals(
            ToolResult.Failure(ToolError(ErrorCode.NOT_FOUND, "missing", "look elsewhere")),
            failure,
        )
    }
}
