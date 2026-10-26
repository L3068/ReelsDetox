package dev.reelsguard.monitoring

import dev.reelsguard.core.UsageState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Serial durable writes on their own coroutine; a slow disk must never delay accessibility actions.
 * Counters are cumulative, so a newer queued snapshot safely supersedes intermediate snapshots.
 */
internal class UsageCheckpoints(
    scope: CoroutineScope,
    write: suspend (UsageState) -> Boolean,
    failed: () -> Unit,
) {
    private val pending = Channel<UsageState>(Channel.CONFLATED)
    var isFailed = false
        private set
    init {
        scope.launch {
            for (state in pending) {
                if (!write(state)) {
                    isFailed = true
                    pending.close()
                    failed()
                    break
                }
            }
        }
    }
    fun offer(state: UsageState): Boolean = pending.trySend(state).isSuccess
}
