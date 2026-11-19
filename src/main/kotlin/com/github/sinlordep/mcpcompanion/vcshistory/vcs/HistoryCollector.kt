package com.github.sinlordep.mcpcompanion.vcshistory.vcs

import com.github.sinlordep.mcpcompanion.common.progress.ide.runUnderIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.history.VcsAbstractHistorySession
import com.intellij.openapi.vcs.history.VcsAppendableHistorySessionPartner
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vcs.history.VcsHistoryProvider

/**
 * Collects the newest [max] revisions of a path from a VCS history provider, which streams them newest first
 * (`reportAppendableHistory`, what the IDE's History tab uses). Once one more than [max] arrived it cancels its own
 * indicator ([runUnderIndicator]), which stops the VCS command; that cancellation is expected and swallowed, any other
 * one (the call was cancelled) is not.
 */
internal class HistoryCollector(private val max: Int) {

    class Collected(val revisions: List<VcsFileRevision>, val hasMore: Boolean)

    private val revisions = ArrayList<VcsFileRevision>()
    private var failure: VcsException? = null

    @Volatile
    private var enough = false

    suspend fun collect(provider: VcsHistoryProvider, path: FilePath): Collected {
        try {
            runUnderIndicator { indicator -> provider.reportAppendableHistory(path, Partner(indicator)) }
        } catch (e: ProcessCanceledException) {
            if (!enough) throw e
        }
        failure?.let { throw it }
        synchronized(revisions) {
            return Collected(revisions.take(max), revisions.size > max)
        }
    }

    private inner class Partner(private val indicator: ProgressIndicator) : VcsAppendableHistorySessionPartner {
        override fun reportCreatedEmptySession(session: VcsAbstractHistorySession?) = Unit

        override fun acceptRevision(revision: VcsFileRevision) {
            synchronized(revisions) {
                if (enough) return
                revisions += revision
                if (revisions.size > max) {
                    enough = true
                    indicator.cancel()
                }
            }
            indicator.checkCanceled()
        }

        override fun reportException(exception: VcsException) {
            failure = exception
        }
    }
}
