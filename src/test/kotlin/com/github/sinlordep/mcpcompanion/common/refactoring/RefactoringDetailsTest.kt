package com.github.sinlordep.mcpcompanion.common.refactoring

import com.github.sinlordep.mcpcompanion.common.result.ErrorCode
import com.github.sinlordep.mcpcompanion.common.source.SourceRange
import com.github.sinlordep.mcpcompanion.extractmethod.MethodExtraction
import com.github.sinlordep.mcpcompanion.pullup.MemberPullUp
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols.code
import com.github.sinlordep.mcpcompanion.testing.FakeSymbols.value
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Client input of `mc_pull_up` and `mc_extract_method`, and code ranges as refactoring targets. */
class RefactoringDetailsTest {

    @Test
    fun `a source range defaults to whole lines and is checked before the file is read`() {
        val range = SourceRange.of(" a/B.java ", 3, null, 5, null).value()
        assertEquals("a/B.java", range.path)
        assertEquals(1, range.startColumn)
        assertNull(range.endColumn)
        assertEquals("lines 3-5 of a/B.java", range.description)
        assertEquals("line 4 of a/B.java", SourceRange.of("a/B.java", 4, 2, 4, 9).value().description)

        val invalid = listOf(
            SourceRange.of(" ", 1, null, 1, null),
            SourceRange.of("a/B.java", null, null, 1, null),
            SourceRange.of("a/B.java", 0, null, 1, null),
            SourceRange.of("a/B.java", 1, 0, 1, null),
            SourceRange.of("a/B.java", 5, null, 4, null),
            SourceRange.of("a/B.java", 4, 7, 4, 7),
        )
        invalid.forEach { assertEquals(it.toString(), ErrorCode.INVALID_ARGUMENT, it.code()) }
    }

    @Test
    fun `a code range is its own target and names itself in messages`() {
        val range = SourceRange.of("a/B.java", 3, null, 5, null).value()
        assertEquals(range, runBlocking { SourceRangeTargets.locate(range) }.value())
        assertEquals("The code at lines 3-5 of a/B.java", SourceRangeTargets.nameOf(range))
    }

    @Test
    fun `pull-up details drop blank target names and duplicates and refuse malformed member names`() {
        val pullUp = MemberPullUp.of("  ", listOf("age", " age ", "size"), null).value()
        assertNull(pullUp.targetClass)
        assertEquals(listOf("age", "size"), pullUp.withMembers)
        assertFalse(pullUp.makeAbstract)
        assertTrue(MemberPullUp.of("com.example.Base", null, true).value().makeAbstract)
        assertEquals(ErrorCode.INVALID_ARGUMENT, MemberPullUp.of(null, listOf(""), null).code())
        assertEquals(ErrorCode.INVALID_ARGUMENT, MemberPullUp.of(null, listOf("a b"), null).code())
    }

    @Test
    fun `an extracted method needs a name`() {
        val extraction = MethodExtraction.of(" total ", Visibility.PUBLIC, true).value()
        assertEquals("total", extraction.methodName)
        assertEquals(Visibility.PUBLIC, extraction.visibility)
        assertEquals(true, extraction.makeStatic)
        assertEquals(ErrorCode.INVALID_ARGUMENT, MethodExtraction.of(" ", null, null).code())
        assertEquals(ErrorCode.INVALID_ARGUMENT, MethodExtraction.of(null, null, null).code())
    }
}
