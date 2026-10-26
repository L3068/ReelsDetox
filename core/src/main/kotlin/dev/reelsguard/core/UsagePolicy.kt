package dev.reelsguard.core

/** Shared accounting decisions; Android supplies actual visibility and elapsed realtime. */
object UsagePolicy {
    fun watching(settings: Settings, foreground: Boolean, classification: Classification, guardVisible: Boolean): Boolean =
        settings.enabled && settings.consent && foreground && classification == Classification.REELS && !guardVisible

    fun checkpoint(previous: UsageState?, current: UsageState, wasViewing: Boolean, viewing: Boolean,
                   elapsedMillis: Long, lastQueuedAt: Long): Boolean =
        current != previous && (previous == null || viewing != wasViewing || current.days.keys != previous.days.keys ||
            (!viewing && current.days != previous.days) ||
            current.zoneId != previous.zoneId || current.bootCount != previous.bootCount ||
            (viewing && elapsedMillis - lastQueuedAt >= 1_000))
}
