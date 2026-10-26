package dev.reelsguard.core

import org.junit.Assert.*
import org.junit.Test

class NavigationSafetyTest {
    @Test fun unchangedViewerGetsOneRetryThenStops() {
        val gate = BackGate()
        assertFalse(gate.retryBudgetExhausted(0))
        assertTrue(gate.observe(Classification.REELS, true, true, 0))
        assertFalse(gate.observe(Classification.REELS, true, true, 1000))
        assertTrue(gate.observe(Classification.REELS, true, true, 1500))
        assertFalse(gate.retryBudgetExhausted(1500))
        assertFalse(gate.retryBudgetExhausted(2999))
        assertTrue(gate.retryBudgetExhausted(3000))
        for (time in listOf(3000L, 6000L, 60_000L)) {
            assertFalse("Unchanged viewer must not loop Back", gate.observe(Classification.REELS, true, true, time))
        }
    }

    @Test fun uncertainScreensAndRuleTogglesDoNotRefillRetries() {
        val gate = BackGate()
        gate.observe(Classification.REELS, true, true, 0)
        gate.observe(Classification.REELS, true, true, 2000)
        gate.observe(Classification.UNKNOWN, true, true, 4000)
        gate.observe(Classification.REELS, true, false, 5000)
        assertFalse(gate.observe(Classification.REELS, true, true, 8000))
        gate.observe(Classification.OTHER, true, true, 9000)
        assertFalse(gate.retryBudgetExhausted(9000))
        assertTrue(gate.observe(Classification.REELS, true, true, 10_000))
        gate.observe(Classification.UNKNOWN, false, true, 11_000)
        assertTrue(gate.observe(Classification.REELS, true, true, 12_000))
    }

    @Test fun coverNeverExtendsEvenOnePixelIntoSystemNavigation() {
        val safe = Bounds(0, 50, 1000, 1900)
        val button = UiNode("com.instagram.android:id/clips_tab", UiLabel.REELS,
            Bounds(400, 1800, 550, 1900), clickable = true)
        val snapshot = ScreenSnapshot(InstagramCandidates.PACKAGE_NAME, listOf(button), Bounds(0, 0, 1000, 2000), safe)
        val settings = Settings(enabled = true, consent = true)
        assertEquals(button.bounds, OverlayPolicy.candidate(snapshot, settings, true))
        assertNull(OverlayPolicy.candidate(snapshot.copy(nodes = listOf(button.copy(bounds = button.bounds.copy(bottom = 1901)))), settings, true))
        assertNull(OverlayPolicy.candidate(snapshot.copy(packageName = "another.app"), settings, true))
        assertNull(OverlayPolicy.candidate(snapshot.copy(nodes = emptyList()), settings, true))
    }
}
