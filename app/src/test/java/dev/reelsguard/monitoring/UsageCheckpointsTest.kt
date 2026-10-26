package dev.reelsguard.monitoring

import dev.reelsguard.core.UsageState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UsageCheckpointsTest {
    @Test fun slowDiskDoesNotBlockEnforcementAndNewestCheckpointIsRetained() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val releaseDisk = CompletableDeferred<Unit>()
        val written = mutableListOf<UsageState>()
        val writer = UsageCheckpoints(scope, { state ->
            releaseDisk.await(); written += state; true
        }, { fail("unexpected storage failure") })
        try {
            writer.offer(UsageState(days = mapOf("2026-10-26" to 1L)))
            writer.offer(UsageState(days = mapOf("2026-10-26" to 2L)))
            writer.offer(UsageState(days = mapOf("2026-10-26" to 3L)))
            assertTrue("Disk is deliberately still blocked", written.isEmpty())
            releaseDisk.complete(Unit)
            yield()
            assertEquals(listOf(1L, 3L), written.map { it.days["2026-10-26"] })
        } finally { scope.cancel() }
    }

    @Test fun failedDiskWriteSignalsFailureAndStopsAcceptingCounters() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var failed = false
        val writer = UsageCheckpoints(scope, { false }, { failed = true })
        try {
            writer.offer(UsageState())
            yield()
            assertTrue(failed)
            assertTrue(writer.isFailed)
            assertFalse(writer.offer(UsageState()))
        } finally { scope.cancel() }
    }
}
