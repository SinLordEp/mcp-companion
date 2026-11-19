package com.github.sinlordep.mcpcompanion.common.source.psi

import com.github.sinlordep.mcpcompanion.common.source.SourceRange
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange

/** The offsets of [range] in this document, or null when it reaches outside a line or the document. */
fun Document.textRangeOf(range: SourceRange): TextRange? {
    val start = offsetOf(range.startLine, range.startColumn) ?: return null
    val end = if (range.endColumn == null) {
        range.endLine.takeIf { it <= lineCount }?.let { getLineEndOffset(it - 1) }
    } else {
        offsetOf(range.endLine, range.endColumn)
    }
    return end?.takeIf { it >= start }?.let { TextRange(start, it) }
}

/** "12:9-14:31": the 1-based start and (exclusive) end positions of [range] in this document. */
fun Document.positionsOf(range: TextRange): String {
    fun position(offset: Int): String {
        val line = getLineNumber(offset)
        return "${line + 1}:${offset - getLineStartOffset(line) + 1}"
    }
    return "${position(range.startOffset)}-${position(range.endOffset)}"
}

private fun Document.offsetOf(line: Int, column: Int): Int? {
    if (line > lineCount) return null
    val offset = getLineStartOffset(line - 1) + column - 1
    return offset.takeIf { it <= getLineEndOffset(line - 1) }
}
