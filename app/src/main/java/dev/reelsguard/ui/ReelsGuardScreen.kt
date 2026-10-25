package dev.reelsguard.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.reelsguard.R
import dev.reelsguard.core.Mode
import dev.reelsguard.core.Settings
import dev.reelsguard.data.AppRepository
import dev.reelsguard.monitoring.RuntimeState
import dev.reelsguard.monitoring.ServiceStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class Page { HOME, DISCLOSURE, PERMISSIONS, SETTINGS, PRIVACY, ABOUT }

@Composable
fun ReelsGuardScreen(repository: AppRepository) {
    val state by repository.state.collectAsStateWithLifecycle()
    val service by RuntimeState.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(repository, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                repository.refreshToday()
                delay(1_000)
            }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    var onboardingDismissed by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val enabledInAndroid = rememberAccessibilityEnabled()
    val settingsFailure = stringResource(R.string.settings_unavailable)
    val saveFailure = stringResource(R.string.save_failed)
    fun openAccessibility() {
        try {
            context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: android.content.ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar(settingsFailure) }
        } catch (_: SecurityException) {
            scope.launch { snackbar.showSnackbar(settingsFailure) }
        }
    }
    fun save(transform: (Settings) -> Settings) {
        scope.launch {
            try { repository.updateSettings(transform) }
            catch (_: java.io.IOException) { snackbar.showSnackbar(saveFailure) }
        }
    }
    val showOnboarding = state.ready && !state.settings.consent && !onboardingDismissed && page == Page.HOME
    val scroll = rememberScrollState()
    LaunchedEffect(page, showOnboarding) { scroll.scrollTo(0) }
    BackHandler(enabled = page != Page.HOME || showOnboarding) {
        onboardingDismissed = true
        page = Page.HOME
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (page != Page.HOME) {
                    TextButton(onClick = { page = Page.HOME }) { Text(stringResource(R.string.back_home)) }
                }
                Brand()
                if (state.error) {
                    InfoCard(R.string.status_storage_error, R.string.storage_error_body)
                } else if (!state.ready) {
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    Text(stringResource(R.string.loading))
                } else if (showOnboarding) {
                    Onboarding(
                        onContinue = { page = Page.DISCLOSURE },
                        onSkip = { onboardingDismissed = true },
                    )
                } else when (page) {
                    Page.HOME -> Dashboard(
                        settings = state.settings, usedMs = state.usageTodayMs,
                        service = service, permission = enabledInAndroid, storageError = state.error,
                        onEnabled = { enabled ->
                            if (enabled && !state.settings.consent) page = Page.DISCLOSURE
                            else save { it.copy(enabled = enabled) }
                        },
                        onMode = { mode -> save { it.copy(mode = mode) } },
                        onLimit = { limit -> save { it.copy(dailyLimitMinutes = limit) } },
                        onCover = { cover -> save { it.copy(hideButton = cover) } },
                        onSetup = { page = if (state.settings.consent) Page.PERMISSIONS else Page.DISCLOSURE },
                        onSettings = { page = Page.SETTINGS },
                        onPrivacy = { page = Page.PRIVACY },
                        onAbout = { page = Page.ABOUT },
                    )
                    Page.DISCLOSURE -> Disclosure(
                        busy = busy,
                        onAccept = {
                            busy = true
                            scope.launch {
                                try {
                                    repository.acceptConsent()
                                    // Persistence must succeed before Android's settings are opened.
                                    if (repository.state.value.settings.consent) {
                                        onboardingDismissed = true
                                        page = Page.PERMISSIONS
                                        openAccessibility()
                                    } else snackbar.showSnackbar(saveFailure)
                                } catch (_: java.io.IOException) {
                                    snackbar.showSnackbar(saveFailure)
                                } finally { busy = false }
                            }
                        },
                        onDecline = { onboardingDismissed = true; page = Page.HOME },
                    )
                    Page.PERMISSIONS -> PermissionSetup(
                        consent = state.settings.consent, permission = enabledInAndroid, connected = service.connected,
                        onOpen = { if (state.settings.consent) openAccessibility() else page = Page.DISCLOSURE },
                    )
                    Page.SETTINGS -> SettingsPage(
                        settings = state.settings,
                        onCover = { cover -> save { it.copy(hideButton = cover) } },
                        onPermission = { page = if (state.settings.consent) Page.PERMISSIONS else Page.DISCLOSURE },
                        onPrivacy = { page = Page.PRIVACY },
                        onRevoke = {
                            scope.launch {
                                try {
                                    repository.revokeConsent()
                                    onboardingDismissed = true
                                    page = Page.HOME
                                } catch (_: java.io.IOException) { snackbar.showSnackbar(saveFailure) }
                            }
                        },
                    )
                    Page.PRIVACY -> InformationPage(R.string.privacy_title, listOf(
                        R.string.privacy_local, R.string.privacy_content, R.string.privacy_permissions, R.string.privacy_control,
                    ))
                    Page.ABOUT -> InformationPage(R.string.about_title, listOf(
                        R.string.about_description, R.string.about_license, R.string.limitations, R.string.about_independent,
                    ))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun rememberAccessibilityEnabled(): Boolean {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember { mutableStateOf(false) }
    DisposableEffect(owner, manager) {
        fun refresh() {
            val expected = ComponentName(context.packageName, "dev.reelsguard.monitoring.ReelsAccessibilityService")
            enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val info = it.resolveInfo.serviceInfo
                ComponentName(info.packageName, info.name) == expected
            }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        val listener = AccessibilityManager.AccessibilityStateChangeListener { refresh() }
        refresh()
        owner.lifecycle.addObserver(observer)
        manager.addAccessibilityStateChangeListener(listener)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            manager.removeAccessibilityStateChangeListener(listener)
        }
    }
    return enabled
}

@Composable
private fun Brand() {
    val color = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.size(42.dp)) {
            drawPath(Path().apply {
                moveTo(size.width * .5f, 0f)
                lineTo(size.width * .92f, size.height * .17f)
                lineTo(size.width * .86f, size.height * .66f)
                quadraticTo(size.width * .7f, size.height * .88f, size.width * .5f, size.height)
                quadraticTo(size.width * .3f, size.height * .88f, size.width * .14f, size.height * .66f)
                lineTo(size.width * .08f, size.height * .17f)
                close()
            }, color)
        }
        Column {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.brand_tagline), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Onboarding(onContinue: () -> Unit, onSkip: () -> Unit) {
    Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.welcome_body), style = MaterialTheme.typography.bodyLarge)
    InfoCard(R.string.onboarding_modes, R.string.onboarding_modes_body)
    InfoCard(R.string.onboarding_private, R.string.onboarding_private_body)
    Text(stringResource(R.string.limitations), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.review_access)) }
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.not_now)) }
}

@Composable
private fun Disclosure(busy: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    Text(stringResource(R.string.disclosure_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.disclosure_intro), style = MaterialTheme.typography.bodyLarge)
    InfoCard(R.string.disclosure_reads_title, R.string.disclosure_reads_body)
    InfoCard(R.string.disclosure_action_title, R.string.disclosure_action_body)
    InfoCard(R.string.disclosure_data_title, R.string.disclosure_data_body)
    Text(stringResource(R.string.disclosure_choice))
    Button(onClick = onAccept, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.agree_open_settings))
    }
    TextButton(onClick = onDecline, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.decline))
    }
}

@Composable
private fun Dashboard(
    settings: Settings, usedMs: Long, service: ServiceStatus, permission: Boolean, storageError: Boolean,
    onEnabled: (Boolean) -> Unit, onMode: (Mode) -> Unit, onLimit: (Int) -> Unit, onCover: (Boolean) -> Unit,
    onSetup: () -> Unit, onSettings: () -> Unit, onPrivacy: () -> Unit, onAbout: () -> Unit,
) {
    Text(stringResource(R.string.dashboard_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    val status = when {
        storageError || service.storageError -> R.string.status_storage_error
        !settings.enabled || !settings.consent -> R.string.status_off
        !permission -> R.string.status_permission_needed
        !service.connected -> R.string.status_disconnected
        service.navigationFailed -> R.string.status_navigation_failed
        service.detection == "paused" -> R.string.status_paused
        else -> R.string.status_ready
    }
    val healthy = settings.enabled && settings.consent && permission && service.connected && !storageError && !service.storageError && !service.navigationFailed && service.detection != "paused"
    Card(colors = CardDefaults.cardColors(containerColor = if (healthy) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(status), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(if (healthy) R.string.status_ready_body else R.string.status_inactive_body))
            SwitchRow(stringResource(R.string.protection), stringResource(R.string.protection_description), settings.enabled, onEnabled)
        }
    }
    Text(stringResource(R.string.mode_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    ModeOption(Mode.BLOCK_ALL, settings.mode, R.string.mode_block, R.string.mode_block_body, onMode)
    ModeOption(Mode.DAILY_LIMIT, settings.mode, R.string.mode_limit, R.string.mode_limit_body, onMode)
    if (settings.mode == Mode.DAILY_LIMIT) {
        LimitCard(settings.dailyLimitMinutes, usedMs, onLimit)
    } else {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.used_today))
                Text(duration(usedMs), fontWeight = FontWeight.Bold)
            }
        }
    }
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp)) {
            SwitchRow(stringResource(R.string.cover_title), stringResource(R.string.cover_description), settings.hideButton, onCover)
        }
    }
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.accessibility_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(when {
                !settings.consent -> R.string.permission_no_consent
                !permission -> R.string.permission_off
                !service.connected -> R.string.permission_waiting
                else -> R.string.permission_connected
            }))
            OutlinedButton(onClick = onSetup) { Text(stringResource(R.string.permission_setup)) }
        }
    }
    Text(stringResource(R.string.limitations), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onSettings) { Text(stringResource(R.string.settings_title)) }
        TextButton(onClick = onPrivacy) { Text(stringResource(R.string.privacy_title)) }
        TextButton(onClick = onAbout) { Text(stringResource(R.string.about_title)) }
    }
}

@Composable
private fun ModeOption(mode: Mode, selected: Mode, title: Int, body: Int, onMode: (Mode) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().selectable(selected = mode == selected, role = Role.RadioButton, onClick = { onMode(mode) }),
        shape = RoundedCornerShape(20.dp),
        color = if (mode == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = mode == selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun LimitCard(limit: Int, usedMs: Long, onLimit: (Int) -> Unit) {
    var draft by remember(limit) { mutableFloatStateOf(limit.toFloat()) }
    val limitMs = limit * 60_000L
    val label = stringResource(R.string.daily_allowance)
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(androidx.compose.ui.res.pluralStringResource(R.plurals.minutes_value, draft.roundToInt(), draft.roundToInt()), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Slider(
                value = draft, onValueChange = { draft = it }, valueRange = 1f..120f, steps = 118,
                onValueChangeFinished = { onLimit(draft.roundToInt()) },
                modifier = Modifier.semantics { contentDescription = label },
            )
            LinearProgressIndicator(progress = { (usedMs.toFloat() / limitMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric(R.string.used_today, duration(usedMs), Modifier.weight(1f))
                Metric(R.string.remaining, duration((limitMs - usedMs).coerceAtLeast(0L)), Modifier.weight(1f))
            }
            Text(stringResource(R.string.reset_midnight), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Metric(label: Int, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun duration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return stringResource(R.string.duration_value, seconds / 60, seconds % 60)
}

@Composable
private fun PermissionSetup(consent: Boolean, permission: Boolean, connected: Boolean, onOpen: () -> Unit) {
    Text(stringResource(R.string.permission_setup), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(stringResource(when { !consent -> R.string.permission_no_consent; !permission -> R.string.permission_off; !connected -> R.string.permission_waiting; else -> R.string.permission_connected }))
    Text(stringResource(R.string.permission_instructions))
    InfoCard(R.string.overlay_title, R.string.overlay_body)
    Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.open_accessibility)) }
    Text(stringResource(R.string.permission_troubleshooting), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun SettingsPage(settings: Settings, onCover: (Boolean) -> Unit, onPermission: () -> Unit, onPrivacy: () -> Unit, onRevoke: () -> Unit) {
    var showRevoke by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp)) {
            SwitchRow(stringResource(R.string.cover_title), stringResource(R.string.cover_description), settings.hideButton, onCover)
        }
    }
    Text(stringResource(R.string.appearance_system), color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = onPermission, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.permission_setup)) }
    OutlinedButton(onClick = onPrivacy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.privacy_title)) }
    if (settings.consent) {
        TextButton(onClick = { showRevoke = true }) { Text(stringResource(R.string.revoke_consent), color = MaterialTheme.colorScheme.error) }
    }
    if (showRevoke) {
        AlertDialog(
            onDismissRequest = { showRevoke = false },
            title = { Text(stringResource(R.string.revoke_consent)) },
            text = { Text(stringResource(R.string.revoke_body)) },
            confirmButton = { TextButton(onClick = { showRevoke = false; onRevoke() }) { Text(stringResource(R.string.revoke_confirm)) } },
            dismissButton = { TextButton(onClick = { showRevoke = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChecked, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun InfoCard(title: Int, body: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun InformationPage(title: Int, paragraphs: List<Int>) {
    Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    paragraphs.forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyLarge) }
}
