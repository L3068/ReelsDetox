package dev.reelsguard.overlay

import android.accessibilityservice.AccessibilityService
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.view.Display
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.View
import dev.reelsguard.core.Bounds

/** Child of Instagram's surface: System UI stays above it and it cannot cover another app. */
@androidx.annotation.RequiresApi(34)
internal class AttachedViewerGuard(
    service: AccessibilityService,
    val windowId: Int,
    val windowBounds: Bounds,
    view: View,
    bounds: Bounds,
) {
    private val host: SurfaceControlViewHost
    private val surfacePackage: SurfaceControlViewHost.SurfacePackage
    private var current = bounds

    init {
        val display = requireNotNull(service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))
        @Suppress("DEPRECATION")
        host = SurfaceControlViewHost(service.createDisplayContext(display), display, null as IBinder?)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 37) host.setView(view,
                SurfaceControlViewHost.LayoutParams(bounds.right - bounds.left, bounds.bottom - bounds.top, false))
            else host.setView(view, bounds.right - bounds.left, bounds.bottom - bounds.top)
            surfacePackage = requireNotNull(host.surfacePackage)
            service.attachAccessibilityOverlayToWindow(windowId, surfacePackage.surfaceControl)
            position(bounds)
        } catch (failure: RuntimeException) {
            host.release()
            throw failure
        }
    }

    fun update(bounds: Bounds) {
        if (bounds == current) return
        if (android.os.Build.VERSION.SDK_INT >= 37) host.relayout(
            SurfaceControlViewHost.LayoutParams(bounds.right - bounds.left, bounds.bottom - bounds.top, false))
        else host.relayout(bounds.right - bounds.left, bounds.bottom - bounds.top)
        position(bounds)
        current = bounds
    }

    private fun position(bounds: Bounds) {
        SurfaceControl.Transaction().use {
            it.setPosition(surfacePackage.surfaceControl, (bounds.left - windowBounds.left).toFloat(),
                (bounds.top - windowBounds.top).toFloat())
                .setLayer(surfacePackage.surfaceControl, Int.MAX_VALUE).setVisibility(surfacePackage.surfaceControl, true).apply()
        }
    }

    fun release() {
        try {
            SurfaceControl.Transaction().use { it.reparent(surfacePackage.surfaceControl, null).apply() }
        } finally {
            surfacePackage.release()
            host.release()
        }
    }
}
