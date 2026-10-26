package dev.reelsguard.overlay

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import dev.reelsguard.core.Bounds
import dev.reelsguard.core.CoverTheme

/** A visible, touch-consuming cover; Instagram's actual navigation button is not removed. */
internal class ReelsButtonCover(private val service: AccessibilityService, private val viewerGuard: Boolean = false) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var current: Bounds? = null
    private var attached: AttachedViewerGuard? = null
    private var currentColor: Int? = null
    val visible: Boolean get() = view != null || attached != null
    val attachedWindowId: Int? get() = if (android.os.Build.VERSION.SDK_INT >= 34) attached?.windowId else null
    val attachedWindowBounds: Bounds? get() = if (android.os.Build.VERSION.SDK_INT >= 34) attached?.windowBounds else null

    @android.annotation.SuppressLint("RtlHardcoded") // Screen bounds are absolute left coordinates, even in RTL.
    fun show(bounds: Bounds, theme: CoverTheme = CoverTheme.SYSTEM, windowId: Int? = null, windowBounds: Bounds = bounds) {
        val dark = when (theme) {
            CoverTheme.SYSTEM -> service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            CoverTheme.LIGHT -> false
            CoverTheme.DARK -> true
        }
        // Flat, opaque Instagram navigation colors; no rounded edges revealing the hidden icon.
        val color = if (viewerGuard) Color.rgb(24, 28, 27) else if (dark) Color.rgb(12, 16, 20) else Color.WHITE
        if (currentColor != color) view?.let { if (!viewerGuard) it.setBackgroundColor(color) }
        currentColor = color
        if (viewerGuard && android.os.Build.VERSION.SDK_INT >= 34 && windowId != null && windowBounds.contains(bounds)) {
            try {
                val old = attached
                if (old != null && old.windowId == windowId && old.windowBounds == windowBounds) old.update(bounds)
                else {
                    hide()
                    attached = AttachedViewerGuard(service, windowId, windowBounds, createView(color), bounds)
                }
            } catch (_: RuntimeException) { hide() }
            return
        }
        if (bounds == current && view != null) return
        val existing = view
        if (existing != null) {
            val params = (existing.layoutParams as WindowManager.LayoutParams).apply {
                width = bounds.right - bounds.left
                height = bounds.bottom - bounds.top
                x = bounds.left
                y = bounds.top
            }
            try {
                manager.updateViewLayout(existing, params)
                current = bounds
                return
            } catch (_: RuntimeException) {
                hide()
            }
        }
        val cover = createView(color)
        val params = WindowManager.LayoutParams(
            bounds.right - bounds.left, bounds.bottom - bounds.top,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = bounds.left; y = bounds.top
            if (android.os.Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        try {
            manager.addView(cover, params)
            view = cover; current = bounds
        } catch (_: RuntimeException) { view = null; current = null }
    }

    private fun createView(color: Int) = TextView(service).apply {
            text = if (viewerGuard) service.getString(dev.reelsguard.R.string.viewer_blocked_message) else ""
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            contentDescription = service.getString(dev.reelsguard.R.string.reels_blocked)
            setBackgroundColor(color)
            isClickable = true
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { /* Intentionally consume touches only within the identified Reels button. */ }
    }

    fun hide() {
        val old = view
        val child = attached
        view = null; current = null; attached = null; currentColor = null
        if (old != null) try { manager.removeViewImmediate(old) } catch (_: RuntimeException) { /* Already detached. */ }
        if (child != null && android.os.Build.VERSION.SDK_INT >= 34) try { child.release() } catch (_: RuntimeException) { /* Parent was destroyed. */ }
    }
}
