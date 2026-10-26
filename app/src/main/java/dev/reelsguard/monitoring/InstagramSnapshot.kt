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
import dev.reelsguard.core.AttachedGuardPolicy
import dev.reelsguard.core.GuardWindow
import dev.reelsguard.core.WindowKind
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

    @Volatile var activeInstagram: Boolean? = null
        private set
    var currentWindowBounds: Bounds? = null
        private set

    fun foreground(expectedWindowId: Int?, allowed: () -> Boolean): Boolean {
        if (!allowed() || !interactive()) return false
        val root = service.rootInActiveWindow ?: return false
        return try { allowed() && root.packageName?.toString() == INSTAGRAM && root.windowId == expectedWindowId && safeWindow(root.windowId) }
        finally { root.release() }
    }

    /** Only an already window-bound guard may survive System UI taking focus. No tree read. */
    fun retainGuard(windowId: Int, bounds: Bounds): Boolean {
        if (!interactive()) return false
        val windows = service.windows
        try {
            val evidence = windows.map { window -> GuardWindow(window.id, when (window.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> WindowKind.APPLICATION
                AccessibilityWindowInfo.TYPE_SYSTEM -> WindowKind.SYSTEM
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowKind.INPUT
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowKind.OVERLAY
                else -> WindowKind.OTHER
            }, Rect().also(window::getBoundsInScreen).domain(), window.isActive, window.isFocused,
                Build.VERSION.SDK_INT < 30 || window.displayId == android.view.Display.DEFAULT_DISPLAY) }
            return AttachedGuardPolicy.retain(windowId, bounds, true, evidence)
        } finally { windows.forEach { it.release() } }
    }

    private fun safeWindow(id: Int): Boolean {
        val windows = service.windows
        try {
            val active = windows.firstOrNull { it.id == id } ?: run { windowState = "active_window_missing"; return false }
            windowState = "type=${active.type},active=${active.isActive},windows=${windows.size}"
            currentWindowBounds = Rect().also(active::getBoundsInScreen).domain()
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

    fun read(allowed: () -> Boolean): ScreenSnapshot? {
        activeInstagram = null
        currentWindowBounds = null
        if (!allowed()) return null
        if (!interactive()) { activeInstagram = false; return null }
        // Explicit prefetch avoids a sequence of synchronous findById/getParent Binder calls.
        val root = if (Build.VERSION.SDK_INT >= 33) {
            val windows = service.windows
            try { if (!allowed()) null else windows.firstOrNull { it.isActive && it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
                ?.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID) }
            finally { windows.forEach { it.release() } }
        } else if (allowed()) service.rootInActiveWindow else null
        if (root == null) return null
        try {
            if (!allowed()) return null
            activeInstagram = root.packageName?.toString()?.let { it == INSTAGRAM }
            if (activeInstagram != true || !safeWindow(root.windowId)) return null
            val app = Rect().also(root::getBoundsInScreen)
            if (app.isEmpty) return null
            val safe = safeArea(app)
            val nodes = mutableListOf<UiNode>()
            var visited = 0
            var complete = true
            fun visit(node: AccessibilityNodeInfo, ancestors: Set<String>, depth: Int) {
                if (!allowed()) { complete = false; return }
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
                    if (!allowed()) { complete = false; break }
                    if (visited >= 500) { complete = false; break }
                    val child = node.getChild(i) ?: continue
                    try { visit(child, next, depth + 1) } finally { child.release() }
                }
            }
            visit(root, emptySet(), 0)
            return ScreenSnapshot(INSTAGRAM, nodes.toList(), app.domain(), safe.domain(), complete,
                root.windowId, currentWindowBounds ?: app.domain())
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
