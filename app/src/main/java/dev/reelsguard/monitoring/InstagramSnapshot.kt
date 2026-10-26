package dev.reelsguard.monitoring

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.reelsguard.core.Bounds
import dev.reelsguard.core.InstagramCandidates
import dev.reelsguard.core.ScreenSnapshot
import dev.reelsguard.core.UiNode
import dev.reelsguard.core.UiLabels

internal const val INSTAGRAM = "com.instagram.android"

/** Reads only a confirmed foreground Instagram tree. No text, descriptions or nodes are logged. */
internal class InstagramSnapshot(private val service: AccessibilityService) {
    var windowState = "not_checked"
        private set
    private val overlayContext by lazy {
        if (Build.VERSION.SDK_INT >= 30) {
            service.getSystemService(android.hardware.display.DisplayManager::class.java)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)?.let { display ->
                    service.createDisplayContext(display)
                        .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                }
        } else null
    }
    fun interactive(): Boolean = service.getSystemService(PowerManager::class.java).isInteractive &&
        !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** Package identity only, for Back episode reset. Inaccessible content is UNKNOWN, not exit. */
    fun instagramActive(): Boolean? {
        if (!interactive()) return false
        return try {
            val root = service.rootInActiveWindow ?: return null
            try { root.packageName?.toString()?.let { it == INSTAGRAM } }
            finally { root.release() }
        } catch (_: RuntimeException) { null }
    }

    fun foreground(): Boolean {
        if (!interactive()) return false
        return try {
            val root = service.rootInActiveWindow ?: return false
            try { root.packageName?.toString() == INSTAGRAM && safeWindow(root.windowId) }
            finally { root.release() }
        } catch (_: RuntimeException) { false }
    }

    private fun safeWindow(id: Int): Boolean {
        val windows = service.windows
        try {
            val active = windows.firstOrNull { it.id == id } ?: run { windowState = "active_window_missing"; return false }
            windowState = "type=${active.type},active=${active.isActive},windows=${windows.size}"
            if (active.type != AccessibilityWindowInfo.TYPE_APPLICATION || !active.isActive) return false
            if (Build.VERSION.SDK_INT >= 30 && active.displayId != android.view.Display.DEFAULT_DISPLAY) return false
            // Background application windows are not evidence of focus loss. The active root,
            // focused windows and exact app bounds still constrain every action/overlay.
            return windows.none {
                it.id != id && it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                    (it.isFocused || it.isActive || it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD)
            }
        } finally { windows.forEach { it.release() } }
    }

    fun read(): ScreenSnapshot? {
        if (!interactive()) return null
        val root = service.rootInActiveWindow ?: return null
        try {
            if (root.packageName?.toString() != INSTAGRAM || !safeWindow(root.windowId)) return null
            val app = Rect().also(root::getBoundsInScreen)
            if (app.isEmpty) return null
            val safe = safeArea(app)
            val nodes = mutableListOf<UiNode>()
            var visited = 0
            var complete = true
            fun visit(node: AccessibilityNodeInfo, ancestors: Set<String>, depth: Int) {
                if (++visited > 500 || depth > 35) { complete = false; return }
                if (!node.isVisibleToUser) return
                val id = node.viewIdResourceName?.takeIf { it in InstagramCandidates.allIds }
                val bounds = Rect().also(node::getBoundsInScreen).domain()
                // Exact UI labels are reduced to enums immediately. Never read node.text (DM/post content).
                val label = UiLabels.parse(node.contentDescription)
                if (id != null || label != null) {
                    nodes += UiNode(id, label, bounds, node.isSelected, node.isClickable, ancestors, true)
                }
                val next = if (id == null) ancestors else ancestors + id
                for (i in 0 until node.childCount) {
                    if (visited >= 500) { complete = false; break }
                    val child = node.getChild(i) ?: continue
                    try { visit(child, next, depth + 1) } finally { child.release() }
                }
            }
            fun ancestors(node: AccessibilityNodeInfo): Set<String> {
                val ids = mutableSetOf<String>()
                var parent = node.parent
                var depth = 0
                while (parent != null && depth++ < 35) {
                    val current = parent
                    current.viewIdResourceName?.takeIf { it in InstagramCandidates.allIds }?.let(ids::add)
                    parent = current.parent
                    current.release()
                }
                parent?.release()
                return ids
            }
            fun lookup(ids: Set<String>) {
                for (id in ids) {
                    val matches = root.findAccessibilityNodeInfosByViewId(id)
                    if (matches.size > 40) complete = false
                    for ((index, node) in matches.withIndex()) {
                        try {
                            if (index < 40 && node.isVisibleToUser && node.windowId == root.windowId &&
                                node.packageName?.toString() == INSTAGRAM) {
                                val parentIds = if (id in InstagramCandidates.actionIds) ancestors(node) else emptySet()
                                nodes += UiNode(id, UiLabels.parse(node.contentDescription),
                                    Rect().also(node::getBoundsInScreen).domain(), node.isSelected, node.isClickable, parentIds)
                            }
                        } finally { node.release() }
                    }
                }
            }
            // Server-side ID queries avoid hundreds of child IPCs on a newly rendered feed.
            lookup(InstagramCandidates.navigationIds + InstagramCandidates.viewerIds +
                InstagramCandidates.otherNavigationIds + InstagramCandidates.otherScreenIds)
            val hasViewer = nodes.any { it.viewId in InstagramCandidates.viewerIds }
            fun snapshot() = ScreenSnapshot(INSTAGRAM, nodes.toList(), app.domain(), safe.domain(), complete)
            if (!hasViewer) return snapshot()
            if (dev.reelsguard.core.CandidateInstagramClassifier().classify(snapshot()) != dev.reelsguard.core.Classification.UNKNOWN) return snapshot()
            lookup(InstagramCandidates.actionIds)
            if (dev.reelsguard.core.CandidateInstagramClassifier().classify(snapshot()) != dev.reelsguard.core.Classification.UNKNOWN) return snapshot()
            // Unknown viewer variants can still use exact closed labels with bounded traversal.
            nodes.clear()
            visit(root, emptySet(), 0)
            return snapshot()
        } finally { root.release() }
    }

    private fun safeArea(app: Rect): Rect {
        if (Build.VERSION.SDK_INT < 30) return Rect() // Back fallback; no guessed navigation insets.
        val visual = overlayContext ?: return Rect()
        val metrics = visual.getSystemService(WindowManager::class.java).currentWindowMetrics
        // Gesture recognition regions extend into app buttons (Pixel 8: 21 px above the
        // navigation bar). Android gives those gestures priority; exclude actual system bars
        // and cutouts, rather than treating the entire gesture region as forbidden UI space.
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val safe = Rect(metrics.bounds).apply {
            left += insets.left; top += insets.top; right -= insets.right; bottom -= insets.bottom
        }
        return if (safe.intersect(app)) safe else Rect()
    }


}

internal fun Rect.domain() = Bounds(left, top, right, bottom)
@Suppress("DEPRECATION")
private fun AccessibilityNodeInfo.release() { if (Build.VERSION.SDK_INT < 33) recycle() }
@Suppress("DEPRECATION")
private fun AccessibilityWindowInfo.release() { if (Build.VERSION.SDK_INT < 33) recycle() }
