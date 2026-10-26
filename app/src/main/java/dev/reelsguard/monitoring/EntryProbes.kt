package dev.reelsguard.monitoring

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A short settling burst after app entry, not an idle polling loop. Window animations can
 * expose the package before the navigation nodes, with no further accessibility event.
 * Explicit clicks need a full burst because readiness may still describe the screen being left.
 */
internal class EntryProbes(
    private val scope: CoroutineScope,
    private val eligible: () -> Boolean,
    private val ready: () -> Boolean,
    private val scan: () -> Unit,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private var job: Job? = null
    fun start(waitForSettling: Boolean = false) {
        if (job?.isActive == true) {
            if (!waitForSettling) return
            // The latest explicit click replaces an old burst; there is only one sampling job.
            job?.cancel()
        }
        job = scope.launch {
            repeat(20) { attempt ->
                // Always sample a new entry once; readiness may describe the previous screen.
                if (!eligible() || (!waitForSettling && attempt > 0 && ready())) return@launch
                scan()
                pause(50)
            }
        }
    }
    fun stop() { job?.cancel(); job = null }
}
