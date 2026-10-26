package dev.reelsguard.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.reelsguard.ReelsGuardApp
import dev.reelsguard.core.CoverTheme
import dev.reelsguard.core.Mode
import dev.reelsguard.core.UsageState
import dev.reelsguard.core.UsageTracker
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    @Test fun settingsWriteCannotRollBackAnUnsavedServiceHandoff() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = (context.applicationContext as ReelsGuardApp).repository
        val initial = withTimeout(5000) { repository.state.first { it.ready } }
        val day = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val expected = (initial.usage.days[day] ?: 0L) + 1_000L
        val pending = initial.usage.copy(days = initial.usage.days + (day to expected))
        repository.publishUsage(pending)
        val settings = initial.settings.copy(dailyLimitMinutes = if (initial.settings.dailyLimitMinutes == 120) 119 else 120)
        // This transaction returns/emits the older disk ledger because the handoff is not saved.
        repository.updateSettings { settings }
        assertEquals(expected, repository.state.value.usage.days[day])
        assertEquals(settings, repository.state.value.settings)
        assertTrue(repository.saveUsage(pending))
    }

    @Test fun reconnectUsesFinalCheckpointBeforeItsDiskWriteCompletes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as ReelsGuardApp
        val repository = app.repository
        withTimeout(5000) { repository.state.first { it.ready } }
        val wall = Instant.now()
        val zone = ZoneId.systemDefault()
        val elapsed = android.os.SystemClock.elapsedRealtime()
        val day = wall.atZone(zone).toLocalDate().toString()
        val initial = repository.state.value.usage
        val before = (initial.days[day] ?: 0L) + 1_000L
        val seed = initial.copy(days = initial.days + (day to before),
            logicalEpochMillis = wall.toEpochMilli(), lastWallEpochMillis = wall.toEpochMilli(),
            lastElapsedMillis = elapsed, zoneId = zone.id, bootCount = context.bootCount())
        assertTrue(repository.saveUsage(seed))
        val finalOldSession = seed.copy(days = seed.days + (day to before + 100L),
            logicalEpochMillis = wall.plusMillis(100).toEpochMilli(), lastElapsedMillis = elapsed + 100L)
        var reconnectBaseline = UsageState()
        var finalNewSession = UsageState()
        instrumentation.runOnMainSync {
            app.checkpoint(finalOldSession)
            // Stay in this main-thread turn: asynchronous DataStore completion cannot resume
            // the application's checkpoint coroutine before a new service reads its baseline.
            reconnectBaseline = repository.state.value.usage
            val tracker = UsageTracker(reconnectBaseline)
            tracker.update(true, elapsed + 100, wall.plusMillis(100), zone, seed.bootCount)
            finalNewSession = tracker.update(false, elapsed + 200, wall.plusMillis(200), zone, seed.bootCount)
            app.checkpoint(finalNewSession)
        }
        assertEquals("A reconnect must inherit the unsaved final tail", before + 100L, reconnectBaseline.days[day])
        assertEquals("Both service sessions must count", before + 200L, finalNewSession.days[day])
        assertTrue(repository.saveUsage(finalNewSession))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val restored = AppRepository(context, scope)
            val durable = withTimeout(5000) { restored.state.first { it.ready } }
            assertEquals(before + 200L, durable.usage.days[day])
        } finally { scope.cancel() }
    }

    @Test fun settingsConsentAndCounterSurviveRepositoryRecreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ReelsGuardApp
        val repository = app.repository
        withTimeout(5000) { repository.state.first { it.ready } }
        repository.acceptConsent()
        repository.updateSettings { it.copy(enabled = true, mode = Mode.DAILY_LIMIT, dailyLimitMinutes = 7, coverTheme = CoverTheme.DARK) }
        assertTrue(repository.state.value.settings.consent)
        assertTrue(repository.saveUsage(UsageState(days = mapOf("2026-10-25" to 420_000L))))
        // A stale checkpoint must not erase a later durable counter.
        assertTrue(repository.saveUsage(UsageState(days = mapOf("2026-10-25" to 20_000L))))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val restored = AppRepository(context, scope)
            val state = withTimeout(5000) { restored.state.first { it.ready } }
            assertEquals(7, state.settings.dailyLimitMinutes)
            assertEquals(Mode.DAILY_LIMIT, state.settings.mode)
            assertEquals(CoverTheme.DARK, state.settings.coverTheme)
            assertEquals(420_000L, state.usage.days["2026-10-25"])
            repository.revokeConsent()
            assertFalse(repository.state.value.settings.enabled)
            assertFalse(repository.state.value.settings.consent)
        } finally { scope.cancel() }
    }

    @Test fun codecRoundTripAndCorruptionDoesNotResetSilently() {
        val usage = UsageState(mapOf("2026-10-25" to 120_000L), 1000, 999, 500, "Europe/Budapest")
        assertEquals(usage, UsageCodec.decode(UsageCodec.encode(usage)))
        assertThrows(Exception::class.java) { UsageCodec.decode("not-json") }
        assertThrows(Exception::class.java) { UsageCodec.decode("{\"days\":{\"2026-10-25\":-1}}") }
    }
}
