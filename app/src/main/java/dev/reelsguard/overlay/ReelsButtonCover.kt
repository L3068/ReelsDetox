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
import android.os.Handler
import android.os.Looper

/** A visible, touch-consuming cover; Instagram's actual navigation button is not removed. */
internal class ReelsButtonCover(private val service: AccessibilityService) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var current: Bounds? = null
    private val handler = Handler(Looper.getMainLooper())
    private val delayedHide = Runnable { hide() }
    val visible: Boolean get() = view != null

    @android.annotation.SuppressLint("RtlHardcoded") // Screen bounds are absolute left coordinates, even in RTL.
    fun show(bounds: Bounds) {
        handler.removeCallbacks(delayedHide)
        if (bounds == current && view != null) return
        if (view != null) {
            val params = (view!!.layoutParams as WindowManager.LayoutParams).apply {
                width = bounds.right - bounds.left
                height = bounds.bottom - bounds.top
                x = bounds.left
                y = bounds.top
            }
            try {
                manager.updateViewLayout(view, params)
                current = bounds
                return
            } catch (_: RuntimeException) {
                hide()
            }
        }
        val cover = TextView(service).apply {
            text = ""
            gravity = Gravity.CENTER
            contentDescription = "Reels blocked"
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 28, 27)); cornerRadius = 8f * resources.displayMetrics.density
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
        handler.removeCallbacks(delayedHide)
        val old = view
        view = null; current = null
        if (old != null) try { manager.removeViewImmediate(old) } catch (_: RuntimeException) { /* Already detached. */ }
    }

    fun hideDelayed(delayMillis: Long = 250L) {
        handler.removeCallbacks(delayedHide)
        handler.postDelayed(delayedHide, delayMillis)
    }
}
