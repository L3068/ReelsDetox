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
import dev.reelsguard.core.UiLabel
import dev.reelsguard.core.UiNode
import java.util.Locale

internal const val INSTAGRAM = "com.instagram.android"

/** Reads only a confirmed foreground Instagram tree. No text, descriptions or nodes are logged. */
internal class InstagramSnapshot(private val service: AccessibilityService) {
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
            val active = windows.firstOrNull { it.id == id } ?: return false
            if (active.type != AccessibilityWindowInfo.TYPE_APPLICATION || !active.isActive) return false
            if (Build.VERSION.SDK_INT >= 30 && active.displayId != android.view.Display.DEFAULT_DISPLAY) return false
            // Multi-window, keyboards and system panels are conservatively unsupported.
            if (windows.count { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } != 1) return false
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
                val label = label(node.contentDescription)
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
            visit(root, emptySet(), 0)
            return ScreenSnapshot(INSTAGRAM, nodes, app.domain(), safe.domain(), complete)
        } finally { root.release() }
    }

    private fun safeArea(app: Rect): Rect {
        if (Build.VERSION.SDK_INT < 30) return Rect() // Back fallback; no guessed navigation insets.
        val metrics = service.getSystemService(WindowManager::class.java).maximumWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.systemGestures())
        val safe = Rect(metrics.bounds).apply {
            left += insets.left; top += insets.top; right -= insets.right; bottom -= insets.bottom
        }
        return if (safe.intersect(app)) safe else Rect()
    }

    private fun label(description: CharSequence?): UiLabel? = when (description?.toString()?.trim()?.lowercase(Locale.ROOT)) {
        "reels" -> UiLabel.REELS
        "like" -> UiLabel.LIKE
        "comment" -> UiLabel.COMMENT
        "share" -> UiLabel.SHARE
        "home" -> UiLabel.HOME
        "stories" -> UiLabel.STORIES
        "direct", "messages" -> UiLabel.DIRECT
        else -> null
    }
}

internal fun Rect.domain() = Bounds(left, top, right, bottom)
@Suppress("DEPRECATION")
private fun AccessibilityNodeInfo.release() { if (Build.VERSION.SDK_INT < 33) recycle() }
@Suppress("DEPRECATION")
private fun AccessibilityWindowInfo.release() { if (Build.VERSION.SDK_INT < 33) recycle() }
