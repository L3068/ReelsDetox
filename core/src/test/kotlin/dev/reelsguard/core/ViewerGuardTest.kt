package dev.reelsguard.core

import org.junit.Assert.*
import org.junit.Test

class ViewerGuardTest {
    @Test fun guardCoversOnlyVerifiedViewerInsideSystemInsets() {
        val app = Bounds(0, 132, 1080, 2337)
        val viewer = Bounds(0, 132, 1080, 2211)
        val nodes = listOf(
            UiNode("com.instagram.android:id/clips_viewer_view_pager", bounds = viewer),
            UiNode("com.instagram.android:id/clips_tab", bounds = Bounds(216, 2211, 432, 2337), selected = true, clickable = true))
        val screen = ScreenSnapshot(InstagramCandidates.PACKAGE_NAME, nodes, app, app)
        val settings = Settings(enabled = true, consent = true, hideButton = false)
        assertEquals(viewer, OverlayPolicy.viewerGuard(screen, settings, true))
        assertNull(OverlayPolicy.viewerGuard(screen, settings, false))
        assertNull(OverlayPolicy.viewerGuard(screen.copy(nodes = nodes.takeLast(1)), settings, true))
        assertNull(OverlayPolicy.viewerGuard(screen.copy(packageName = "another.app"), settings, true))
        assertNull(OverlayPolicy.viewerGuard(screen.copy(complete = false), settings, true))
        val oversized = UiNode("com.instagram.android:id/clips_viewer_container", bounds = Bounds(-100, -100, 1200, 2600))
        assertEquals(viewer, OverlayPolicy.viewerGuard(screen.copy(nodes = nodes + oversized), settings, true))
    }
}
