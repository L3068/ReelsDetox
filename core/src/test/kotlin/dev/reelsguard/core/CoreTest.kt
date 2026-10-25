package dev.reelsguard.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CoreTest {
    private val utc = ZoneId.of("UTC")
    private val day = Instant.parse("2026-01-01T12:00:00Z")
    private val app = Bounds(0, 0, 1000, 2000)
    private val enabled = Settings(enabled = true, consent = true)
    private fun screen(vararg nodes: UiNode, complete: Boolean = true) = ScreenSnapshot(
        "com.instagram.android", nodes.toList(), app, Bounds(0, 50, 1000, 1900), complete)
    private val viewer = "com.instagram.android:id/clips_viewer_view_pager"
    private fun reelNodes() = arrayOf(
        UiNode(viewer, bounds = Bounds(0, 50, 1000, 1800)),
        UiNode("com.instagram.android:id/clips_like_button", UiLabel.LIKE,
            Bounds(850, 1200, 950, 1300), clickable = true, ancestorViewIds = setOf(viewer)),
        UiNode("com.instagram.android:id/clips_comment_button", UiLabel.COMMENT,
            Bounds(850, 1350, 950, 1450), clickable = true, ancestorViewIds = setOf(viewer)))

    @Test fun `consent and enablement gate every mode`() {
        assertFalse(ProtectionPolicy.shouldBlock(Settings(), 999999))
        assertFalse(ProtectionPolicy.shouldBlock(enabled.copy(consent = false), 999999))
        assertTrue(ProtectionPolicy.shouldBlock(enabled, 0))
        val limited = enabled.copy(mode = Mode.DAILY_LIMIT, dailyLimitMinutes = 1)
        assertFalse(ProtectionPolicy.shouldBlock(limited, 59999))
        assertTrue(ProtectionPolicy.shouldBlock(limited, 60000))
    }

    @Test fun `invalid limits fail validation`() {
        for (limit in listOf(0, 121)) {
            try { Settings(dailyLimitMinutes = limit); fail("accepted invalid limit") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `generic actions and a reels tab do not establish viewer`() {
        val s = screen(UiNode(label = UiLabel.REELS, selected = true),
            UiNode(label = UiLabel.LIKE), UiNode(label = UiLabel.COMMENT))
        assertEquals(Classification.UNKNOWN, CandidateInstagramClassifier().classify(s))
    }

    @Test fun `viewer needs two related corroborating actions`() {
        val c = CandidateInstagramClassifier()
        assertEquals(Classification.UNKNOWN, c.classify(screen(reelNodes()[0])))
        assertEquals(Classification.UNKNOWN, c.classify(screen(*reelNodes().map {
            it.copy(ancestorViewIds = emptySet()) }.toTypedArray())))
        assertEquals(Classification.REELS, c.classify(screen(*reelNodes())))
    }

    @Test fun `incomplete invisible or conflicting trees fail open`() {
        val c = CandidateInstagramClassifier()
        assertEquals(Classification.UNKNOWN, c.classify(screen(*reelNodes(), complete = false)))
        assertEquals(Classification.UNKNOWN, c.classify(screen(*reelNodes().map {
            it.copy(visible = false) }.toTypedArray())))
        assertEquals(Classification.UNKNOWN, c.classify(screen(*reelNodes(),
            UiNode("com.instagram.android:id/reel_viewer", bounds = app))))
        assertEquals(Classification.OTHER, c.classify(screen(
            UiNode("com.instagram.android:id/reel_viewer", bounds = app))))
        assertEquals(Classification.OTHER, c.classify(screen(*reelNodes()).copy(packageName = "another.app")))
    }

    @Test fun `ordinary feed and direct messages do not trigger blocking`() {
        val c = CandidateInstagramClassifier()
        for (id in listOf("feed_timeline", "direct_thread_message_list", "profile_grid")) {
            assertEquals(Classification.OTHER, c.classify(screen(
                UiNode("com.instagram.android:id/$id", bounds = app),
                UiNode(label = UiLabel.LIKE), UiNode(label = UiLabel.COMMENT))))
        }
    }

    @Test fun `overlay requires exact unique bounded navigation target`() {
        val button = UiNode("com.instagram.android:id/clips_tab", UiLabel.REELS,
            Bounds(400, 1750, 550, 1870), clickable = true)
        assertEquals(button.bounds, OverlayPolicy.candidate(screen(button), enabled, true))
        val localizedButton = button.copy(viewId = null)
        assertEquals(localizedButton.bounds, OverlayPolicy.candidate(screen(localizedButton), enabled, true))
        val partiallySystemCovered = button.copy(bounds = Bounds(400, 1800, 550, 1950))
        assertEquals(partiallySystemCovered.bounds,
            OverlayPolicy.candidate(screen(partiallySystemCovered), enabled, true))
        assertNull(OverlayPolicy.candidate(screen(button), enabled, false))
        assertNull(OverlayPolicy.candidate(screen(button), enabled.copy(hideButton = false), true))
        assertNull(OverlayPolicy.candidate(screen(button), Settings(), true))
        assertNull(OverlayPolicy.candidate(screen(button, button.copy(bounds = Bounds(600, 1750, 750, 1870))), enabled, true))
        for (bounds in listOf(Bounds(400, 1950, 550, 2100), app, Bounds(400, 100, 550, 220), Bounds(-1, 1750, 150, 1870))) {
            assertNull(OverlayPolicy.candidate(screen(button.copy(bounds = bounds)), enabled, true))
        }
        assertNull(OverlayPolicy.candidate(screen(button.copy(viewId = null, label = null)), enabled, true))
        assertNull(OverlayPolicy.candidate(screen(button, complete = false), enabled, true))
    }

    @Test fun `back gate repeats blocked attempts after cooldown`() {
        val gate = BackGate()
        assertTrue(gate.observe(Classification.REELS, true, true, 0))
        assertFalse(gate.observe(Classification.UNKNOWN, true, true, 2000))
        assertTrue(gate.observe(Classification.REELS, true, true, 4000))
        assertFalse(gate.observe(Classification.OTHER, true, true, 5000))
        assertTrue(gate.observe(Classification.REELS, true, true, 6000))
        assertFalse(gate.observe(Classification.UNKNOWN, false, true, 7000))
        assertTrue(gate.observe(Classification.REELS, true, true, 8000))
    }

    @Test fun `cooldown covers separate viewer episodes and disable cannot retrigger`() {
        val gate = BackGate(cooldownMillis = 1500)
        assertTrue(gate.observe(Classification.REELS, true, true, 0))
        gate.observe(Classification.REELS, true, false, 100)
        assertTrue(gate.observe(Classification.REELS, true, true, 2000))
        gate.observe(Classification.OTHER, true, true, 2100)
        assertTrue(gate.observe(Classification.REELS, true, true, 2200))
        gate.observe(Classification.OTHER, true, true, 2300)
        assertTrue(gate.observe(Classification.REELS, true, true, 2400))
        assertTrue(gate.observe(Classification.REELS, true, true, 4000))
    }

    @Test fun `tracker counts only previously active foreground intervals`() {
        val tracker = UsageTracker()
        tracker.update(true, 0, day, utc)
        tracker.update(false, 5000, day.plusSeconds(5), utc)
        tracker.update(false, 15000, day.plusSeconds(15), utc)
        tracker.update(true, 20000, day.plusSeconds(20), utc)
        tracker.update(false, 23000, day.plusSeconds(23), utc)
        assertEquals(8000, tracker.usedTodayMillis)
    }

    @Test fun `midnight divides usage exactly into local days`() {
        val tracker = UsageTracker()
        val start = Instant.parse("2026-01-01T23:59:58Z")
        tracker.update(true, 0, start, utc)
        val state = tracker.update(false, 5000, start.plusSeconds(5), utc)
        assertEquals(2000L, state.days["2026-01-01"])
        assertEquals(3000L, state.days["2026-01-02"])
        assertEquals(3000L, tracker.usedTodayMillis)
    }

    @Test fun `spring DST day is 23 hours and autumn day is 25 hours`() {
        val budapest = ZoneId.of("Europe/Budapest")
        for ((start, duration, date) in listOf(
            Triple("2026-03-28T23:00:00Z", 23L * 3600000, "2026-03-29"),
            Triple("2026-10-24T22:00:00Z", 25L * 3600000, "2026-10-25"))) {
            val tracker = UsageTracker()
            val instant = Instant.parse(start)
            tracker.update(true, 0, instant, budapest)
            val state = tracker.update(false, duration + 1000, instant.plusMillis(duration + 1000), budapest)
            assertEquals(duration, state.days[date])
            assertEquals(1000, tracker.usedTodayMillis)
        }
    }

    @Test fun `active clock jumps do not grant reset or negative usage`() {
        val tracker = UsageTracker()
        tracker.update(true, 0, day, utc)
        tracker.update(true, 1000, day.plusSeconds(86400), utc)
        tracker.update(false, 2000, day.minusSeconds(86400), utc)
        assertEquals(2000, tracker.usedTodayMillis)
        assertEquals(setOf("2026-01-01"), tracker.snapshot().days.keys)
    }

    @Test fun `restoration preserves quota and does not charge downtime`() {
        val tracker = UsageTracker()
        tracker.update(true, 10000, day, utc)
        val saved = tracker.update(true, 15000, day.plusSeconds(5), utc)
        val restored = UsageTracker(saved)
        restored.update(true, 75000, day.plusSeconds(65), utc)
        restored.update(false, 78000, day.plusSeconds(68), utc)
        assertEquals(8000, restored.usedTodayMillis)
        assertEquals(8000, restored.snapshot().usedOn(day.plusSeconds(68), utc))
    }

    @Test fun `same boot restart ignores wall jump and keeps ledger`() {
        val tracker = UsageTracker()
        tracker.update(true, 1000, day, utc)
        val saved = tracker.update(false, 2000, day.plusSeconds(1), utc)
        val restored = UsageTracker(saved)
        restored.update(false, 3000, day.plusSeconds(86400), utc)
        assertEquals(1000, restored.usedTodayMillis)
    }

    @Test fun `timezone changes carry quota and preserve previous date`() {
        val tracker = UsageTracker()
        val t = Instant.parse("2026-01-01T23:00:00Z")
        tracker.update(true, 0, t, utc)
        tracker.update(false, 1000, t.plusSeconds(1), utc)
        val east = ZoneId.of("Europe/Budapest")
        tracker.update(false, 2000, t.plusSeconds(2), east)
        assertEquals(1000, tracker.usedTodayMillis)
        tracker.update(true, 3000, t.plusSeconds(3), east)
        tracker.update(false, 4000, t.plusSeconds(4), east)
        val state = tracker.update(false, 5000, t.plusSeconds(5), utc)
        assertEquals(2000, tracker.usedTodayMillis)
        assertEquals(2000L, state.days["2026-01-02"])
    }

    @Test fun `settings transitions cannot erase recorded time`() {
        val tracker = UsageTracker()
        tracker.update(true, 0, day, utc)
        tracker.update(false, 60000, day.plusSeconds(60), utc)
        assertTrue(ProtectionPolicy.shouldBlock(enabled.copy(mode = Mode.DAILY_LIMIT, dailyLimitMinutes = 1), tracker.usedTodayMillis))
        assertFalse(ProtectionPolicy.shouldBlock(enabled.copy(enabled = false), tracker.usedTodayMillis))
        tracker.update(false, 90000, day.plusSeconds(90), utc)
        assertTrue(ProtectionPolicy.shouldBlock(enabled, tracker.usedTodayMillis))
        assertTrue(ProtectionPolicy.shouldBlock(enabled.copy(mode = Mode.DAILY_LIMIT, dailyLimitMinutes = 1), tracker.usedTodayMillis))
    }
    @Test fun `disconnected display advances midnight without charging downtime`() {
        val tracker = UsageTracker()
        val start = Instant.parse("2026-01-01T23:59:50Z")
        tracker.update(true, 1000, start, utc)
        val saved = tracker.update(false, 6000, start.plusSeconds(5), utc)
        assertEquals(5000L, saved.usedOn(start.plusSeconds(6), utc, 7000))
        assertEquals(0L, saved.usedOn(start.plusSeconds(11), utc, 12000))
        assertEquals(5000L, saved.days["2026-01-01"])
    }

    @Test fun `disconnected display ignores forward wall jump on same boot`() {
        val tracker = UsageTracker()
        tracker.update(true, 1000, day, utc)
        val saved = tracker.update(false, 6000, day.plusSeconds(5), utc)
        assertEquals(5000L, saved.usedOn(day.plusSeconds(86400), utc, 7000))
    }

    @Test fun `reboot rollback preserves recorded day and charges no downtime`() {
        val tracker = UsageTracker()
        tracker.update(true, 100000, day, utc)
        val saved = tracker.update(true, 105000, day.plusSeconds(5), utc)
        val restored = UsageTracker(saved)
        restored.update(false, 1000, day.minusSeconds(86400), utc)
        assertEquals(5000L, restored.usedTodayMillis)
        assertEquals(5000L, saved.usedOn(day.minusSeconds(86400), utc, 1000))
    }

}
