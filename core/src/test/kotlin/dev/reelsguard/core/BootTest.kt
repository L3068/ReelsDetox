package dev.reelsguard.core

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class BootTest {
    private val zone = ZoneId.of("Europe/Budapest")
    @Test fun rebootWithLongerUptimeDoesNotDelayDailyReset() {
        val before = Instant.parse("2026-10-24T12:00:00Z")
        val after = Instant.parse("2026-10-25T13:00:00Z")
        val saved = UsageState(mapOf("2026-10-24" to 60_000L), before.toEpochMilli(), before.toEpochMilli(), 10_000, zone.id, 4)
        val restored = UsageTracker(saved)
        val state = restored.update(false, 20_000, after, zone, 5)
        assertEquals(after.toEpochMilli(), state.logicalEpochMillis)
        assertEquals(0L, restored.usedTodayMillis)
        assertEquals(60_000L, state.days["2026-10-24"])
        assertEquals(5, state.bootCount)
        assertEquals(0L, saved.usedOn(after, zone, 20_000, 5))
    }
    @Test fun sameBootForwardWallJumpCannotRefillQuota() {
        val before = Instant.parse("2026-10-24T12:00:00Z")
        val saved = UsageState(mapOf("2026-10-24" to 60_000L), before.toEpochMilli(), before.toEpochMilli(), 10_000, zone.id, 4)
        assertEquals(60_000L, saved.usedOn(before.plusSeconds(86400), zone, 20_000, 4))
    }
}
