package dev.reelsguard.core

import java.time.Instant
import java.time.ZoneId

enum class Mode { BLOCK_ALL, DAILY_LIMIT }
enum class CoverTheme { SYSTEM, LIGHT, DARK }

data class Settings(
    val enabled: Boolean = false,
    val consent: Boolean = false,
    val mode: Mode = Mode.BLOCK_ALL,
    val dailyLimitMinutes: Int = 15,
    val hideButton: Boolean = true,
    val coverTheme: CoverTheme = CoverTheme.SYSTEM,
) {
    init { require(dailyLimitMinutes in 1..120) { "Daily limit must be between 1 and 120 minutes" } }
}

enum class Classification { REELS, OTHER, UNKNOWN }

data class Bounds(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0) {
    val width: Long get() = right.toLong() - left
    val height: Long get() = bottom.toLong() - top
    val valid: Boolean get() = width > 0 && height > 0
    fun contains(other: Bounds): Boolean = valid && other.valid &&
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
}

/** Only closed-vocabulary UI labels may cross the Android adapter boundary; never private text. */
enum class UiLabel { REELS, LIKE, COMMENT, SHARE, HOME, STORIES, DIRECT }

data class UiNode(
    val viewId: String? = null,
    val label: UiLabel? = null,
    val bounds: Bounds = Bounds(),
    val selected: Boolean = false,
    val clickable: Boolean = false,
    val ancestorViewIds: Set<String> = emptySet(),
    val visible: Boolean = true,
)

data class ScreenSnapshot(
    val packageName: String?,
    val nodes: List<UiNode>,
    val appBounds: Bounds,
    val safeBounds: Bounds = appBounds,
    val complete: Boolean = true,
    val windowId: Int? = null,
    val windowBounds: Bounds = appBounds,
)

/** Serializable ledger only. No screen content or viewing history beyond daily totals. */
data class UsageState(
    val days: Map<String, Long> = emptyMap(),
    val logicalEpochMillis: Long? = null,
    val lastWallEpochMillis: Long? = null,
    val lastElapsedMillis: Long? = null,
    val zoneId: String? = null,
    val bootCount: Int? = null,
)

/** Read-only date projection for a disconnected UI; pass elapsedRealtime to advance across midnight
 * without counting downtime. Without an elapsed value it conservatively uses the persisted date.
 * Zone changes preview the same carry-forward quota rule as UsageTracker.update.
 */
fun UsageState.usedOn(wall: Instant, zone: ZoneId, elapsedMillis: Long? = null, bootCount: Int? = null): Long {
    val logical = logicalEpochMillis
    val savedElapsed = lastElapsedMillis
    val acceptedMillis = when {
        logical == null -> wall.toEpochMilli()
        elapsedMillis == null -> logical
        (this.bootCount != null && bootCount != null && this.bootCount != bootCount) ||
            savedElapsed == null || elapsedMillis < savedElapsed -> maxOf(logical, wall.toEpochMilli())
        else -> {
            val delta = elapsedMillis - savedElapsed
            if (logical > Long.MAX_VALUE - delta) Long.MAX_VALUE else logical + delta
        }
    }
    val accepted = Instant.ofEpochMilli(acceptedMillis)
    val previousZone = zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone
    val current = (days[accepted.atZone(zone).toLocalDate().toString()] ?: 0).coerceAtLeast(0)
    if (previousZone == zone) return current
    val previous = (days[accepted.atZone(previousZone).toLocalDate().toString()] ?: 0).coerceAtLeast(0)
    return maxOf(current, previous)
}
