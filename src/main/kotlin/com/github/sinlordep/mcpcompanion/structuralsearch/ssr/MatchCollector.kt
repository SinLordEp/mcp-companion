package com.github.sinlordep.mcpcompanion.structuralsearch.ssr

import com.github.sinlordep.mcpcompanion.common.source.SourceKey
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.psi.PsiFile
import com.intellij.structuralsearch.MatchResult
import com.intellij.structuralsearch.MatchResultSink
import com.intellij.structuralsearch.MatchingProcess

/**
 * Receives the matches of one search, keyed by file and offset, until [limit] are in; then it stops the matching
 * process and cancels [indicator] (the caller catches that cancellation). Matches without a file (none are expected)
 * are dropped. The search runs under [indicator], so cancelling the tool call cancels it too.
 */
internal class MatchCollector(private val limit: Int, private val indicator: ProgressIndicator) : MatchResultSink {

    private val matches = LinkedHashMap<SourceKey, MatchResult>()
    private var process: MatchingProcess? = null

    /** The matches by key, the first one per key. */
    @Synchronized
    fun matches(): Map<SourceKey, MatchResult> = LinkedHashMap(matches)

    /** Whether [limit] matches are in, so that the search was stopped. */
    @Synchronized
    fun isFull(): Boolean = matches.size >= limit

    @Synchronized
    override fun newMatch(result: MatchResult) {
        if (matches.size >= limit) return
        val key = keyOf(result) ?: return
        matches.putIfAbsent(key, result)
        if (matches.size >= limit) {
            process?.stop()
            indicator.cancel()
        }
    }

    override fun processFile(element: PsiFile) = Unit

    @Synchronized
    override fun setMatchingProcess(matchingProcess: MatchingProcess) {
        process = matchingProcess
    }

    override fun matchingFinished() = Unit

    override fun getProgressIndicator(): ProgressIndicator = indicator

    private fun keyOf(result: MatchResult): SourceKey? {
        val element = result.match ?: return null
        val file = element.containingFile?.virtualFile ?: return null
        return SourceKey(file.url, element.textRange.startOffset + result.start.coerceAtLeast(0))
    }
}
