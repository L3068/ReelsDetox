package dev.reelsguard.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import dev.reelsguard.core.Bounds

/** A visible, touch-consuming cover; Instagram's actual navigation button is not removed. */
internal class ReelsButtonCover(private val service: AccessibilityService) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var current: Bounds? = null
    val visible: Boolean get() = view != null

    @android.annotation.SuppressLint("RtlHardcoded") // Screen bounds are absolute left coordinates, even in RTL.
    fun show(bounds: Bounds) {
        if (bounds == current && view != null) return
        hide()
        val cover = TextView(service).apply {
            text = "\u2014"
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(Color.WHITE)
            contentDescription = "ReelsGuard"
            background = GradientDrawable().apply {
                setColor(Color.rgb(31, 43, 40)); cornerRadius = 8f * resources.displayMetrics.density
            }
            isClickable = true
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { /* Intentionally consume touches only within the identified Reels button. */ }
        }
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

    fun hide() {
        val old = view
        view = null; current = null
        if (old != null) try { manager.removeViewImmediate(old) } catch (_: RuntimeException) { /* Already detached. */ }
    }
}
