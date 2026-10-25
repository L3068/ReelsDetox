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
    private val classifier = CandidateInstagramClassifier()
    private val back = BackGate()
    private var tracker: UsageTracker? = null
    private var lastSaved: UsageState? = null
    private var lastSaveAt = 0L
    private var wasViewing = false
    private var ticker: Job? = null
    private var revision = 0L
    private var connected = false
    private var receiverRegistered = false
    private var navigationFailed = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) cover.hide()
            signal()
        }
    }

    override fun onServiceConnected() {
        if (connected) return
        connected = true
        reader = InstagramSnapshot(this)
        cover = ReelsButtonCover(this)
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
                if (!it.first.enabled || !it.first.consent || !it.second || it.third) cover.hide()
                signal()
            }
        }
        scope.launch {
            for (ignored in signals) {
                try { evaluate() }
                catch (_: RuntimeException) {
                    cover.hide(); stopTicker()
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
        val foreground = reader.foreground()
        if (!foreground) cover.hide()
        // Content events from background apps are ignored; metadata-only window events are needed
        // across all packages to promptly remove covers on app/system-window transitions.
        if (event.packageName?.toString() != INSTAGRAM && foreground &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) cover.hide()
        signal()
    }

    private fun signal() { revision++; signals.trySend(Unit) }

    private suspend fun evaluate() {
        val observedRevision = revision
        val app = repository.state.value
        if (!app.ready || app.error) {
            cover.hide(); stopTicker(); pauseAndPersist()
            RuntimeState.publish(ServiceStatus(connected = true, storageError = app.error, detection = "paused"))
            return
        }
        val timing = tracker ?: UsageTracker(app.usage).also { tracker = it; lastSaved = app.usage }
        val settings = app.settings
        val authorized = settings.enabled && settings.consent
        val screen = if (authorized) reader.read() else null
        val classification = screen?.let(classifier::classify) ?: Classification.UNKNOWN
        val foreground = screen != null
        val viewing = authorized && foreground && classification == Classification.REELS
        val now = SystemClock.elapsedRealtime()
        val usage = timing.update(viewing, now, Instant.now(), ZoneId.systemDefault(), bootCount())
        val blocking = ProtectionPolicy.shouldBlock(settings, timing.usedTodayMillis)
        if (usage != lastSaved && (viewing != wasViewing || usage.days != lastSaved?.days ||
                (viewing && now - lastSaveAt >= 1_000))) {
            if (!repository.saveUsage(usage)) {
                cover.hide(); stopTicker()
                timing.update(false, SystemClock.elapsedRealtime(), Instant.now(), ZoneId.systemDefault(), bootCount())
                wasViewing = false
                RuntimeState.publish(ServiceStatus(connected = true, storageError = true, detection = "paused"))
                return
            }
            lastSaved = usage; lastSaveAt = now
        }
        wasViewing = viewing
        // No global action or overlay may use a snapshot made stale while DataStore suspended.
        if (observedRevision != revision || !connected) { cover.hide(); return }
        if (!authorized || !foreground || !reader.foreground()) {
            cover.hide(); stopTicker()
            back.observe(Classification.UNKNOWN, reader.instagramActive() != false, false, now)
            navigationFailed = false
            RuntimeState.publish(ServiceStatus(connected = true, detection = "idle"))
            return
        }
        if (back.observe(classification, true, blocking, now)) {
            cover.hide()
            navigationFailed = !performGlobalAction(GLOBAL_ACTION_BACK)
        } else {
            val bounds = OverlayPolicy.candidate(screen!!, settings, blocking)
            if (bounds != null && reader.foreground()) cover.show(bounds) else cover.hide()
        }
        if (classification == Classification.OTHER) navigationFailed = false
        RuntimeState.publish(ServiceStatus(
            connected = true,
            detection = classification.name.lowercase(),
            overlayVisible = cover.visible,
            navigationFailed = navigationFailed,
        ))
        // No idle/background polling. A one-second tick is needed for an unmoving Reels viewer
        // to hit the time limit, checkpoint usage, and validate a currently visible cover.
        if (viewing || cover.visible) startTicker() else stopTicker()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch { while (true) { delay(1_000); signal() } }
    }
    private fun stopTicker() { ticker?.cancel(); ticker = null }

    private suspend fun pauseAndPersist() {
        val usage = tracker?.update(false, SystemClock.elapsedRealtime(), Instant.now(), ZoneId.systemDefault(), bootCount()) ?: return
        wasViewing = false
        if (usage != lastSaved && repository.state.value.ready && repository.saveUsage(usage)) lastSaved = usage
    }

    override fun onInterrupt() {
        if (!connected) return
        cover.hide(); stopTicker()
        scope.launch { pauseAndPersist() }
        RuntimeState.publish(ServiceStatus(connected = true, detection = "paused"))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (connected) { cover.hide(); signal() }
    }

    override fun onUnbind(intent: Intent?): Boolean { cleanup(); return super.onUnbind(intent) }
    override fun onDestroy() { cleanup(); super.onDestroy() }

    private fun cleanup() {
        if (!connected) return
        connected = false
        cover.hide(); stopTicker()
        tracker?.update(false, SystemClock.elapsedRealtime(), Instant.now(), ZoneId.systemDefault(), bootCount())?.let {
            (application as ReelsGuardApp).checkpoint(it)
        }
        if (receiverRegistered) { unregisterReceiver(receiver); receiverRegistered = false }
        RuntimeState.publish(ServiceStatus())
        signals.close(); scope.cancel()
    }
}
