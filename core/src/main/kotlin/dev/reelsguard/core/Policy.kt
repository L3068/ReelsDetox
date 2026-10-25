package dev.reelsguard.core

object ProtectionPolicy {
    fun shouldBlock(settings: Settings, usedTodayMillis: Long): Boolean =
        settings.enabled && settings.consent && (settings.mode == Mode.BLOCK_ALL ||
            usedTodayMillis >= settings.dailyLimitMinutes * 60_000L)
}

object OverlayPolicy {
    /** Pure candidate validation. Android must additionally verify the active window and remove
     * existing overlays immediately on foreground/geometry changes before accepting fresh bounds.
     * Ratios reject suspiciously large bounds; they do not synthesize coordinates.
     */
    fun candidate(snapshot: ScreenSnapshot, settings: Settings, blocked: Boolean): Bounds? {
        if (!blocked || !settings.enabled || !settings.consent || !settings.hideButton ||
            snapshot.packageName != InstagramCandidates.PACKAGE_NAME || !snapshot.complete ||
            !snapshot.appBounds.contains(snapshot.safeBounds)) return null
        val candidates = snapshot.nodes.filter { it.visible && it.clickable &&
            it.viewId in InstagramCandidates.navigationIds && it.label == UiLabel.REELS }
        if (candidates.size != 1) return null
        val bounds = candidates.single().bounds
        val safe = snapshot.safeBounds
        return bounds.takeIf {
            safe.contains(it) && it.width <= safe.width / 4 && it.height <= safe.height / 6 &&
                it.width >= safe.width / 30 && it.height >= safe.height / 100 &&
                it.top >= safe.top + safe.height * 3 / 4
        }
    }
}

/** At most one Back attempt per positively identified viewer episode, even if Android rejects
 * the action. UNKNOWN never rearms. Foreground exit or positive OTHER evidence may rearm.
 * Cooldown applies across episodes. All calls must come from one serialized service context.
 */
class BackGate(private val cooldownMillis: Long = 1500) {
    private var attempted = false
    private var lastAttempt: Long? = null

    init { require(cooldownMillis >= 0) }

    fun observe(classification: Classification, instagramForeground: Boolean, shouldBlock: Boolean, elapsedMillis: Long): Boolean {
        if (!instagramForeground || classification == Classification.OTHER) {
            attempted = false
            return false
        }
        if (!shouldBlock || classification != Classification.REELS || attempted) return false
        val last = lastAttempt
        if (last != null && (elapsedMillis < last || elapsedMillis - last < cooldownMillis)) return false
        attempted = true
        lastAttempt = elapsedMillis
        return true
    }
}
