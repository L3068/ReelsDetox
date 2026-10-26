package dev.reelsguard.core

import org.junit.Assert.*
import org.junit.Test

class RapidEntryTest {
    private val app = Bounds(0, 132, 1080, 2337)
    private val viewer = UiNode("com.instagram.android:id/clips_viewer_view_pager", bounds = Bounds(0, 132, 1080, 2211))
    private val tab = UiNode("com.instagram.android:id/clips_tab", UiLabel.REELS,
        Bounds(216, 2211, 432, 2337), clickable = true, selected = true)

    @Test fun aConfirmedNewEntryIsBlockedEvenInsidePreviousCooldown() {
        val gate = BackGate()
        assertTrue(gate.observe(Classification.REELS, true, true, 0))
        gate.observe(Classification.OTHER, true, true, 40)
        assertTrue("Rapid re-entry must not inherit a previous viewer's delay", gate.observe(Classification.REELS, true, true, 80))
    }

    @Test fun aFullViewerAndSelectedExactTabBlockBeforeActionButtonsLoad() {
        assertEquals(Classification.REELS, CandidateInstagramClassifier().classify(
            ScreenSnapshot(InstagramCandidates.PACKAGE_NAME, listOf(viewer, tab), app)))
        assertEquals(Classification.UNKNOWN, CandidateInstagramClassifier().classify(
            ScreenSnapshot(InstagramCandidates.PACKAGE_NAME, listOf(tab), app)))
    }

    @Test fun selectedHomeIsExitEvidenceWhenNoViewerRemains() {
        val home = UiNode("com.instagram.android:id/feed_tab", UiLabel.HOME, Bounds(0, 2211, 216, 2337), selected = true, clickable = true)
        assertEquals(Classification.OTHER, CandidateInstagramClassifier().classify(
            ScreenSnapshot(InstagramCandidates.PACKAGE_NAME, listOf(home, tab.copy(selected = false)), app)))
    }

    @Test fun coverAcceptsTheIdentifiedButtonWithoutDuplicateDescription() {
        assertEquals(tab.bounds, OverlayPolicy.candidate(
            ScreenSnapshot(InstagramCandidates.PACKAGE_NAME, listOf(tab.copy(label = null)), app, app),
            Settings(enabled = true, consent = true), true))
    }
}
