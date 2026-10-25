package dev.reelsguard.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelEvidenceTest {
    private val viewer = "com.instagram.android:id/clips_viewer_view_pager"
    private val screen = Bounds(0, 0, 1080, 2200)
    private fun action(label: UiLabel, parent: String = viewer) = UiNode(
        label = label, bounds = Bounds(950, 1200, 1050, 1300), clickable = true,
        ancestorViewIds = setOf(parent),
    )
    @Test fun exactViewerWithDistinctLocalizedActionsCanWorkWithoutActionIds() {
        val nodes = listOf(UiNode(viewer, bounds = screen), action(UiLabel.LIKE), action(UiLabel.COMMENT))
        assertEquals(Classification.REELS, CandidateInstagramClassifier().classify(
            ScreenSnapshot(InstagramCandidates.PACKAGE_NAME, nodes, screen)))
    }
    @Test fun labelsOutsideViewerAndRepeatedLikeAreNotEvidence() {
        for (nodes in listOf(
            listOf(action(UiLabel.LIKE), action(UiLabel.COMMENT)),
            listOf(UiNode(viewer, bounds = screen), action(UiLabel.LIKE), action(UiLabel.LIKE)),
            listOf(UiNode(viewer, bounds = screen), action(UiLabel.LIKE, "feed"), action(UiLabel.COMMENT, "feed")),
        )) assertEquals(Classification.UNKNOWN, CandidateInstagramClassifier().classify(
            ScreenSnapshot(InstagramCandidates.PACKAGE_NAME, nodes, screen)))
    }
}
