package dev.reelsguard.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.reelsguard.ReelsGuardApp
import dev.reelsguard.core.Mode
import dev.reelsguard.core.UsageState
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
    @Test fun settingsConsentAndCounterSurviveRepositoryRecreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ReelsGuardApp
        val repository = app.repository
        withTimeout(5000) { repository.state.first { it.ready } }
        repository.acceptConsent()
        repository.updateSettings { it.copy(enabled = true, mode = Mode.DAILY_LIMIT, dailyLimitMinutes = 7) }
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
