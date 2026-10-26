package dev.reelsguard.monitoring

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class EntryProbesTest {
    @Test fun aClickUpgradesAnActiveEntryAndTheLastClickOwnsOneBoundedBurst() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val resume = Channel<Unit>()
        var scans = 0
        var waiting = 0
        var mostWaiting = 0
        val probes = EntryProbes(scope, { true }, { true }, { scans++ }, {
            waiting++
            mostWaiting = maxOf(mostWaiting, waiting)
            try { resume.receive() } finally { waiting-- }
        })
        try {
            probes.start()
            assertEquals(1, scans)
            probes.start(waitForSettling = true)
            assertEquals("Click must upgrade the running normal entry", 2, scans)
            probes.start(waitForSettling = true)
            assertEquals(3, scans)
            probes.start()
            assertEquals("Normal entry must not replace the click burst", 3, scans)
            withTimeout(1_000) { repeat(20) { resume.send(Unit) } }
            assertEquals("Only the final click's twenty samples must finish", 22, scans)
            assertEquals(0, waiting)
            assertEquals("Cancelled bursts must not run beside the new one", 1, mostWaiting)
        } finally { probes.stop(); scope.cancel(); resume.close() }
    }

    @Test fun clickSettlingFindsDelayedViewerDespiteTheOldScreenBeingReady() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var time = 0L
        var foundViewer = false
        var scans = 0
        val probes = EntryProbes(scope, { true }, { true }, {
            scans++
            if (time >= 500) foundViewer = true
        }, { time += it })
        try {
            probes.start(waitForSettling = true)
            assertTrue("Ready may still describe the old DM screen", foundViewer)
            assertEquals(20, scans)
            assertEquals(1_000L, time)
        } finally { scope.cancel() }
    }

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
