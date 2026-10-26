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
            it.viewId in InstagramCandidates.navigationIds }
        if (candidates.size != 1) return null
        val bounds = candidates.single().bounds
        val safe = snapshot.safeBounds
        return bounds.takeIf {
            safe.contains(it) &&
                it.width <= safe.width / 4 && it.height <= safe.height / 6 &&
                it.width >= safe.width / 30 && it.height >= safe.height / 100 &&
                it.top >= safe.top + safe.height * 3 / 4
        }
    }

    /** Prevent watching/tapping a confirmed blocked viewer while Back completes or is rejected. */
    fun viewerGuard(snapshot: ScreenSnapshot, settings: Settings, blocked: Boolean): Bounds? {
        if (!blocked || !settings.enabled || !settings.consent ||
            CandidateInstagramClassifier().classify(snapshot) != Classification.REELS) return null
        val viewer = snapshot.nodes.filter { it.visible && it.viewId in InstagramCandidates.viewerIds &&
            snapshot.appBounds.contains(it.bounds) && it.bounds.width >= snapshot.appBounds.width / 2 &&
            it.bounds.height >= snapshot.appBounds.height / 2 }
            .maxByOrNull { it.bounds.height }?.bounds ?: return null
        val safe = snapshot.safeBounds
        return Bounds(maxOf(viewer.left, safe.left), maxOf(viewer.top, safe.top),
            minOf(viewer.right, safe.right), minOf(viewer.bottom, safe.bottom))
            .takeIf { snapshot.appBounds.contains(it) && safe.contains(it) }
    }
}

/** Allows one retry for a still-confirmed blocked viewer after the cooldown. UNKNOWN and rule
 * toggles never refill the retry budget. Foreground exit or positive OTHER resets the episode.
 * All calls must come from one serialized service context.
 */
class BackGate(private val cooldownMillis: Long = 1500) {
    private var lastAttempt: Long? = null
    private var attempts = 0

    init { require(cooldownMillis >= 0) }

    fun observe(classification: Classification, instagramForeground: Boolean, shouldBlock: Boolean, elapsedMillis: Long): Boolean {
        if (!instagramForeground || classification == Classification.OTHER) {
            attempts = 0
            lastAttempt = null
            return false
        }
        if (!shouldBlock || classification != Classification.REELS || attempts >= 2) return false
        val last = lastAttempt
        if (last != null && (elapsedMillis < last || elapsedMillis - last < cooldownMillis)) return false
        lastAttempt = elapsedMillis
        attempts++
        return true
    }

    /** Caller must separately confirm that the blocked viewer is still open on a fresh snapshot. */
    fun retryBudgetExhausted(elapsedMillis: Long): Boolean {
        val last = lastAttempt ?: return false
        return attempts >= 2 && elapsedMillis >= last && elapsedMillis - last >= cooldownMillis
    }
}
