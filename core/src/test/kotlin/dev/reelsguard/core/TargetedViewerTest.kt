package dev.reelsguard.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TargetedViewerTest {
    @Test fun observedPixelActionIdsIdentifySharedViewerWithoutReadingPostText() {
        val viewerId = "com.instagram.android:id/clips_viewer_view_pager"
        val app = Bounds(0, 0, 1080, 2400)
        val viewer = Bounds(0, 132, 1080, 2211)
        val nodes = listOf(
            UiNode(viewerId, bounds = viewer),
            UiNode("com.instagram.android:id/comment_button", bounds = Bounds(925, 1316, 1041, 1432), clickable = true, ancestorViewIds = setOf(viewerId)),
            UiNode("com.instagram.android:id/direct_share_button", bounds = Bounds(925, 1682, 1041, 1798), clickable = true, ancestorViewIds = setOf(viewerId)),
        )
        assertEquals(Classification.REELS, CandidateInstagramClassifier().classify(ScreenSnapshot(InstagramCandidates.PACKAGE_NAME, nodes, app)))
        assertEquals(Classification.UNKNOWN, CandidateInstagramClassifier().classify(ScreenSnapshot(InstagramCandidates.PACKAGE_NAME, nodes.map { it.copy(ancestorViewIds = emptySet()) }, app)))
    }
}
