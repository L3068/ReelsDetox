package dev.reelsguard.core

enum class WindowKind { APPLICATION, SYSTEM, INPUT, OVERLAY, OTHER }
data class GuardWindow(val id: Int, val kind: WindowKind, val bounds: Bounds,
    val active: Boolean = false, val focused: Boolean = false, val defaultDisplay: Boolean = true)

/** Only for an existing API34+ child surface; never authorizes a new cover or Back action. */
object AttachedGuardPolicy {
    fun retain(windowId: Int, bounds: Bounds, interactive: Boolean, windows: List<GuardWindow>): Boolean {
        if (!interactive) return false
        val parent = windows.firstOrNull { it.id == windowId }
        // System UI may exclude Instagram from accessibility's list while its surface is still
        // visible underneath. Native parent attachment, rather than this list, confines the guard.
        if (parent != null && (parent.kind != WindowKind.APPLICATION || !parent.defaultDisplay || parent.bounds != bounds)) return false
        val focused = windows.filter { it.id != windowId && it.kind != WindowKind.OVERLAY && (it.active || it.focused) }
        return focused.isNotEmpty() && focused.all { it.kind == WindowKind.SYSTEM && it.defaultDisplay }
    }
}
