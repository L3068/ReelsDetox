package dev.reelsguard.monitoring

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class EntryProbesTest {
    @Test fun readinessFromThePreviousScreenCannotSkipANewEntry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var scans = 0
        val probes = EntryProbes(scope, { true }, { true }, { scans++ }, {})
        try {
            probes.start()
            assertEquals(1, scans)
        } finally { scope.cancel() }
    }
    @Test fun controlsAppearingAfterAnAnimationAreFoundWithoutAnotherEvent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var time = 0L
        var covered = false
        val scans = mutableListOf<Long>()
        val probes = EntryProbes(scope, { true }, { covered }, {
            scans += time
            if (time >= 100) covered = true
        }, { time += it })
        try {
            probes.start()
            assertTrue(covered)
            assertEquals(listOf(0L, 50L, 100L), scans)
        } finally { scope.cancel() }
    }
    @Test fun unsupportedUiDoesNotStartAnEndlessPollingLoop() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var scans = 0
        var elapsed = 0L
        val probes = EntryProbes(scope, { true }, { false }, { scans++ }, { elapsed += it })
        try {
            probes.start()
            assertEquals(20, scans)
            assertEquals(1000L, elapsed)
        } finally { scope.cancel() }
    }
}
