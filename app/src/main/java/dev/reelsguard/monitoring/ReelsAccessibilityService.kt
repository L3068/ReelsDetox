package dev.reelsguard.monitoring

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import dev.reelsguard.ReelsGuardApp
import dev.reelsguard.data.bootCount
import dev.reelsguard.core.BackGate
import dev.reelsguard.core.CandidateInstagramClassifier
import dev.reelsguard.core.Classification
import dev.reelsguard.core.OverlayPolicy
import dev.reelsguard.core.ProtectionPolicy
import dev.reelsguard.core.UsageState
import dev.reelsguard.core.UsageTracker
import dev.reelsguard.overlay.ReelsButtonCover
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Single main-thread actor serializes settings, accessibility transitions and accounting. */
class ReelsAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val repository get() = (application as ReelsGuardApp).repository
    private lateinit var reader: InstagramSnapshot
    private lateinit var cover: ReelsButtonCover
    private lateinit var guard: ReelsButtonCover
    private lateinit var checkpoints: UsageCheckpoints
    private lateinit var entryProbes: EntryProbes
    private var instagramWasForeground = false
    private val classifier = CandidateInstagramClassifier()
    private val back = BackGate(cooldownMillis = 250)
    private var tracker: UsageTracker? = null
    private var lastQueued: UsageState? = null
    private var lastSaveAt = 0L
    private var wasViewing = false
    private var ticker: Job? = null
    private var revision = 0L
    private var connected = false
    private var receiverRegistered = false
    private var navigationFailed = false
    private var tickInterval = 0L
    private var diagnostics = "not_scanned"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) { clearOverlays(); entryProbes.stop() }
            signal()
        }
    }

    override fun onServiceConnected() {
        if (connected) return
        connected = true
        reader = InstagramSnapshot(this)
        cover = ReelsButtonCover(this)
        guard = ReelsButtonCover(this, viewerGuard = true)
        checkpoints = UsageCheckpoints(scope, repository::saveUsage) { clearOverlays(); stopTicker(); signal() }
        entryProbes = EntryProbes(scope, {
            val app = repository.state.value
            connected && app.ready && !app.error && !checkpoints.isFailed &&
                app.settings.enabled && app.settings.consent
        }, {
            val app = repository.state.value
            val blocking = ProtectionPolicy.shouldBlock(app.settings, tracker?.usedTodayMillis ?: app.usageTodayMs)
            val detection = RuntimeState.status.value.detection
            cover.visible || guard.visible ||
                (!blocking && detection == "reels") ||
                ((!blocking || !app.settings.hideButton) && detection == "other")
        }, { signal() })
        RuntimeState.publish(ServiceStatus(connected = true))
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_DATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        receiverRegistered = true
        scope.launch {
            repository.state.map { Triple(it.settings, it.ready, it.error) }.distinctUntilChanged().collect {
                // User's disable/revoke takes effect before any suspended persistence operation resumes.
                if (!it.first.hideButton) cover.hide()
                if (!it.second || it.third ||
                    !ProtectionPolicy.shouldBlock(it.first, tracker?.usedTodayMillis ?: repository.state.value.usageTodayMs)) {
                    clearOverlays()
                }
                if (reader.instagramActive() == true) entryProbes.start()
                signal()
            }
        }
        scope.launch {
            for (ignored in signals) {
                try { evaluate() }
                catch (failure: RuntimeException) {
                    diagnostics = "scan_failed=${failure.javaClass.simpleName}; " + failure.stackTrace.take(8).joinToString { "${it.className}.${it.methodName}:${it.lineNumber}" }
                    clearOverlays(); stopTicker()
                    pauseAndPersist()
                    RuntimeState.publish(ServiceStatus(connected = true, detection = "unknown"))
                }
            }
        }
        signal()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!connected || event == null) return
        // Overlay's own accessibility events must not create an add/remove feedback loop.
        if (event.packageName?.toString() == packageName && reader.foreground()) return
        val active = reader.instagramActive()
        val entered = active == true && !instagramWasForeground
        if (active != null) instagramWasForeground = active
        val foreground = reader.foreground()
        if (!foreground) clearOverlays()
        if (active == false) entryProbes.stop()
        if (entered || (event.packageName?.toString() == INSTAGRAM &&
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) entryProbes.start()
        // Content events from background apps are ignored; metadata-only window events are needed
        // across all packages to promptly remove covers on app/system-window transitions.
        if (event.packageName?.toString() != INSTAGRAM && foreground &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // The actor never awaits disk I/O; fresh bounds are applied in the next main-thread turn.
        signal()
    }

    private fun signal() { revision++; signals.trySend(Unit) }

    private fun evaluate() {
        val observedRevision = revision
        val app = repository.state.value
        if (!app.ready || app.error || checkpoints.isFailed) {
            clearOverlays(); stopTicker(); pauseAndPersist()
            RuntimeState.publish(ServiceStatus(connected = true, storageError = app.error || checkpoints.isFailed, detection = "paused"))
            return
        }
        val timing = tracker ?: UsageTracker(app.usage).also { tracker = it; lastQueued = app.usage }
        val settings = app.settings
        val authorized = settings.enabled && settings.consent
        val screen = if (authorized) reader.read() else null
        val classification = screen?.let(classifier::classify) ?: Classification.UNKNOWN
        val foreground = screen != null
        val viewing = authorized && foreground && classification == Classification.REELS
        val now = SystemClock.elapsedRealtime()
        val usage = timing.update(viewing, now, Instant.now(), ZoneId.systemDefault(), bootCount())
        val blocking = ProtectionPolicy.shouldBlock(settings, timing.usedTodayMillis)
        val bounds = screen?.let { OverlayPolicy.candidate(it, settings, blocking) }
        val guardBounds = screen?.let { OverlayPolicy.viewerGuard(it, settings, blocking) }
        if (authorized && foreground && reader.foreground()) {
            if (bounds != null) cover.show(bounds) else cover.hide()
            if (guardBounds != null) guard.show(guardBounds) else guard.hide()
        } else clearOverlays()
        diagnostics = "classification=$classification; window=${reader.windowState}; app=${screen?.appBounds}; " +
            "safe=${screen?.safeBounds}; complete=${screen?.complete}; nav=$bounds; guard=$guardBounds; " +
            "coverVisible=${cover.visible}; guardVisible=${guard.visible}"
        // Navigation and visual blocking happen before checkpointing, on fresh foreground evidence.
        if (observedRevision != revision || !connected) return
        if (!authorized || !foreground || !reader.foreground()) {
            clearOverlays(); stopTicker()
            back.observe(Classification.UNKNOWN, reader.instagramActive() != false, false, now)
            navigationFailed = false
        } else if (back.observe(classification, true, blocking, now)) {
            navigationFailed = !performGlobalAction(GLOBAL_ACTION_BACK)
        } else if (blocking && classification == Classification.REELS && back.retryBudgetExhausted(now)) {
            navigationFailed = true
        }
        if (!blocking || classification == Classification.OTHER) navigationFailed = false
        val overlayError = (bounds != null && !cover.visible) || (guardBounds != null && !guard.visible)
        if (usage != lastQueued && (viewing != wasViewing || usage.days != lastQueued?.days ||
                (viewing && now - lastSaveAt >= 1_000))) {
            if (checkpoints.offer(usage)) { lastQueued = usage; lastSaveAt = now }
        }
        wasViewing = viewing
        RuntimeState.publish(ServiceStatus(
            connected = true,
            detection = classification.name.lowercase(),
            overlayVisible = cover.visible || guard.visible,
            navigationFailed = navigationFailed,
            overlayError = overlayError,
        ))
        // Brief fast verification while Back completes; normal viewing/checkpoints use 1 Hz.
        if (viewing || cover.visible || guard.visible) {
            startTicker(if (viewing && blocking && !back.retryBudgetExhausted(now)) 150L else 1_000L)
        } else stopTicker()
    }

    private fun startTicker(interval: Long) {
        if (ticker?.isActive == true && tickInterval == interval) return
        ticker?.cancel()
        tickInterval = interval
        ticker = scope.launch { while (true) { delay(interval); signal() } }
    }
    private fun stopTicker() { ticker?.cancel(); ticker = null }

    private fun pauseAndPersist() {
        val usage = tracker?.update(false, SystemClock.elapsedRealtime(), Instant.now(), ZoneId.systemDefault(), bootCount()) ?: return
        wasViewing = false
        if (usage != lastQueued && repository.state.value.ready && checkpoints.offer(usage)) lastQueued = usage
    }

    private fun clearOverlays() { cover.hide(); guard.hide() }

    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>) {
        if (dev.reelsguard.BuildConfig.DEBUG) writer.println("ReelsGuard: $diagnostics")
    }

    override fun onInterrupt() {
        if (!connected) return
        clearOverlays(); stopTicker(); entryProbes.stop()
        pauseAndPersist()
        RuntimeState.publish(ServiceStatus(connected = true, detection = "paused"))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (connected) { clearOverlays(); signal() }
    }

    override fun onUnbind(intent: Intent?): Boolean { cleanup(); return super.onUnbind(intent) }
    override fun onDestroy() { cleanup(); super.onDestroy() }

    private fun cleanup() {
        if (!connected) return
        connected = false
        clearOverlays(); stopTicker(); entryProbes.stop()
        tracker?.update(false, SystemClock.elapsedRealtime(), Instant.now(), ZoneId.systemDefault(), bootCount())?.let {
            (application as ReelsGuardApp).checkpoint(it)
        }
        if (receiverRegistered) { unregisterReceiver(receiver); receiverRegistered = false }
        RuntimeState.publish(ServiceStatus())
        signals.close(); scope.cancel()
    }
}
