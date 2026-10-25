package dev.reelsguard.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Counts elapsedRealtime milliseconds only during the previous update's verified viewing state.
 * The Android adapter supplies viewing=false on screen-off, loss of foreground, UNKNOWN, disable,
 * or consent revocation. A restored tracker starts paused: process downtime is never billed.
 *
 * After initialization logical civil time advances using the monotonic clock, not changing wall
 * time. Local-midnight boundaries use java.time's actual zone rules (23/25-hour DST days included).
 * Changing zones carries max(old quota, destination quota), preventing free time from zone hopping.
 * Historical dates are retained so revisiting a date cannot reset its quota.
 *
 * Across a same-boot restart the persisted monotonic anchor also advances civil time during downtime
 * without charging it. If the public BOOT_COUNT changes (or elapsedRealtime decreased), wall time is trusted only forward of
 * the saved logical anchor. There is no trusted wall clock in this offline core: clock
 * tampering combined with reboot or clearing app data cannot be prevented. If BOOT_COUNT is unavailable, a reboot whose elapsed
 * value already exceeds the saved value is indistinguishable and may delay a daily reset.
 * Calls must be serialized by the service; this class is intentionally not thread-safe.
 */
class UsageTracker(initial: UsageState = UsageState()) {
    private val days = initial.days.filter { (date, millis) ->
        millis >= 0 && runCatching { LocalDate.parse(date) }.isSuccess
    }.toMutableMap()
    private var logicalMillis = initial.logicalEpochMillis
    private var lastWall = initial.lastWallEpochMillis
    private var lastElapsed = initial.lastElapsedMillis
    private var zone = initial.zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    private var viewing = false
    private var started = false
    private var bootCount = initial.bootCount

    val usedTodayMillis: Long get() {
        val instant = logicalMillis ?: return 0
        val currentZone = zone ?: return 0
        return days[dateAt(instant, currentZone)] ?: 0
    }

    fun update(viewing: Boolean, elapsedMillis: Long, wall: Instant, zone: ZoneId, bootCount: Int? = null): UsageState {
        require(elapsedMillis >= 0) { "Elapsed realtime must be nonnegative" }
        val previousLogical = logicalMillis
        val previousElapsed = lastElapsed
        val previousZone = this.zone ?: zone
        val reboot = this.bootCount != null && bootCount != null && this.bootCount != bootCount
        val delta = if (!reboot && previousElapsed != null && elapsedMillis >= previousElapsed)
            elapsedMillis - previousElapsed else 0
        val nextLogical = when {
            previousLogical == null -> wall.toEpochMilli()
            reboot || previousElapsed == null || elapsedMillis < previousElapsed -> maxOf(previousLogical, wall.toEpochMilli())
            else -> addPositive(previousLogical, delta)
        }
        if (started && this.viewing && previousLogical != null && delta > 0) {
            charge(previousLogical, nextLogical, previousZone)
        }
        // Carry quota across a zone-induced date change, but not a natural local midnight.
        if (this.zone != null && this.zone != zone) {
            val from = dateAt(nextLogical, previousZone)
            val to = dateAt(nextLogical, zone)
            val carried = maxOf(days[from] ?: 0, days[to] ?: 0)
            if (carried > 0) days[to] = carried
        }
        logicalMillis = nextLogical
        lastWall = wall.toEpochMilli()
        lastElapsed = elapsedMillis
        this.zone = zone
        this.bootCount = bootCount
        this.viewing = viewing
        started = true
        return snapshot()
    }

    fun snapshot(): UsageState = UsageState(days.toMap(), logicalMillis, lastWall, lastElapsed, zone?.id, bootCount)

    private fun charge(start: Long, end: Long, zone: ZoneId) {
        var cursor = start
        while (cursor < end) {
            val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val midnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val stop = minOf(midnight, end)
            val key = date.toString()
            days[key] = addPositive(days[key] ?: 0, stop - cursor)
            cursor = stop
        }
    }

    private fun dateAt(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString()

    private fun addPositive(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
}
