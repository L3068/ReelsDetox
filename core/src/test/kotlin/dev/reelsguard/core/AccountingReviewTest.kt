package dev.reelsguard.core

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AccountingReviewTest {
    private val settings = Settings(enabled = true, consent = true, mode = Mode.DAILY_LIMIT)
    @Test fun contentEventsDoNotBypassTheOneSecondCheckpointInterval() {
        val tracker = UsageTracker(UsageState(days = mapOf("2026-10-26" to 0L)))
        val start = Instant.parse("2026-10-26T10:00:00Z")
        var saved: UsageState? = tracker.update(true, 0, start, ZoneId.of("UTC"))
        var savedAt = 0L
        val writes = mutableListOf<Long>()
        for (elapsed in 10L..2500L step 10) {
            val current = tracker.update(true, elapsed, start.plusMillis(elapsed), ZoneId.of("UTC"))
            if (UsagePolicy.checkpoint(saved, current, true, true, elapsed, savedAt)) {
                writes += elapsed; saved = current; savedAt = elapsed
            }
        }
        assertEquals(listOf(1000L, 2000L), writes)
    }
    @Test fun guardedWaitingDoesNotConsumeMoreAllowance() {
        val tracker = UsageTracker()
        val start = Instant.parse("2026-10-26T10:00:00Z")
        val zone = ZoneId.of("UTC")
        tracker.update(UsagePolicy.watching(settings, true, Classification.REELS, false), 0, start, zone)
        tracker.update(UsagePolicy.watching(settings, true, Classification.REELS, true), 500, start.plusMillis(500), zone)
        tracker.update(UsagePolicy.watching(settings, true, Classification.REELS, true), 300500, start.plusMillis(300500), zone)
        assertEquals(500L, tracker.usedTodayMillis)
        assertTrue("A failed/invisible guard must not stop real viewing accounting", UsagePolicy.watching(settings,true,Classification.REELS,false))
    }
    @Test fun stopAndDateOrTimezoneChangesCheckpointImmediately() {
        val previous=UsageState(days=mapOf("2026-10-26" to 100L), zoneId="UTC")
        assertTrue(UsagePolicy.checkpoint(previous,previous.copy(days=mapOf("2026-10-26" to 110L)),true,false,110,100))
        assertTrue(UsagePolicy.checkpoint(previous,previous.copy(days=previous.days+("2026-10-27" to 1L)),true,true,110,100))
        assertTrue(UsagePolicy.checkpoint(previous,previous.copy(zoneId="Europe/Budapest"),true,true,110,100))
    }
    @Test fun pausedEventsDoNotPersistChangingClockAnchors() {
        val tracker = UsageTracker(UsageState(days = mapOf("2026-10-26" to 500L)))
        val start = Instant.parse("2026-10-26T10:00:00Z")
        val saved = tracker.update(false, 0, start, ZoneId.of("UTC"))
        for (elapsed in 10L..2500L step 10) {
            val current = tracker.update(false, elapsed, start.plusMillis(elapsed), ZoneId.of("UTC"))
            assertFalse(UsagePolicy.checkpoint(saved, current, false, false, elapsed, 0))
        }
    }
}
