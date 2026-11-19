package com.github.sinlordep.mcpcompanion.common.progress.ide

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.util.Computable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs blocking platform [work] that checks a progress indicator (analysis runners, VCS commands) on a pooled thread,
 * without a read lock, under an indicator of its own: the coroutine's cancellation cancels it, and [work] may cancel
 * it itself to stop early (e.g. once it has enough results), catching the `ProcessCanceledException` that follows.
 */
suspend fun <T> runUnderIndicator(work: (ProgressIndicator) -> T): T = coroutineScope {
    val indicator = ProgressIndicatorBase()
    val canceller = launch {
        try {
            awaitCancellation()
        } finally {
            indicator.cancel()
        }
    }
    try {
        withContext(Dispatchers.IO) {
            ProgressManager.getInstance().runProcess(Computable { work(indicator) }, indicator)
        }
    } finally {
        canceller.cancel()
    }
}
