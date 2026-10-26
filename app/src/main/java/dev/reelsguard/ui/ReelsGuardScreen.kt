package dev.reelsguard.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private enum class Page { HOME, SETUP, SETTINGS, PRIVACY, ABOUT }

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
    var informationBackPage by rememberSaveable { mutableStateOf(Page.HOME) }
    var setupDismissed by rememberSaveable { mutableStateOf(false) }
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
    fun showInformation(target: Page) {
        informationBackPage = page
        page = target
    }
    fun setupOrOpenAccessibility() {
        if (state.settings.consent) openAccessibility() else page = Page.SETUP
    }
    val showSetup = state.ready && !state.settings.consent && !setupDismissed && page == Page.HOME
    fun goBack() {
        when (page) {
            Page.PRIVACY, Page.ABOUT -> page = informationBackPage
            Page.HOME -> setupDismissed = true
            else -> page = Page.HOME
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(page, showSetup) { scroll.scrollTo(0) }
    BackHandler(enabled = page != Page.HOME || showSetup, onBack = ::goBack)
    val informationPage = page == Page.PRIVACY || page == Page.ABOUT
    val setupVisible = showSetup || page == Page.SETUP
    val settingsLink: (() -> Unit)? = if (page == Page.HOME && !setupVisible && !state.error) {
        { page = Page.SETTINGS }
    } else null
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Surface {
                Box(Modifier.fillMaxWidth().statusBarsPadding(), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (page != Page.HOME) {
                            TextButton(onClick = ::goBack) { Text(stringResource(R.string.back_home), style = MaterialTheme.typography.bodyLarge) }
                        } else Brand()
                    }
                }
            }
        },
        bottomBar = {
            if (!informationPage && (state.ready || state.error)) {
                Surface {
                    Column(Modifier.navigationBarsPadding()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            InformationLinks(
                                onPrivacy = { showInformation(Page.PRIVACY) },
                                onAbout = { showInformation(Page.ABOUT) },
                                onSettings = settingsLink,
                            )
                        }
                    }
                }
            }
        },
    ) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            val bodyHeight = maxHeight
            Column(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(scroll)
                    .heightIn(min = bodyHeight).padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = if (setupVisible && !state.error && !informationPage) {
                    Arrangement.spacedBy(24.dp, Alignment.CenterVertically)
                } else Arrangement.spacedBy(20.dp),
            ) {
                when {
                    page == Page.PRIVACY -> InformationPage(R.string.privacy_title, listOf(
                        R.string.privacy_local, R.string.privacy_content, R.string.privacy_permissions, R.string.privacy_control,
                    ))
                    page == Page.ABOUT -> InformationPage(R.string.about_title, listOf(
                        R.string.about_description, R.string.about_license, R.string.limitations, R.string.about_independent,
                    ))
                    state.error -> InfoCard(R.string.status_storage_error, R.string.storage_error_body)
                    !state.ready -> {
                        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                        Text(stringResource(R.string.loading))
                    }
                    setupVisible -> QuickSetup(
                        busy = busy,
                        onAccept = {
                            busy = true
                            scope.launch {
                                try {
                                    repository.updateSettings { it.copy(consent = true, enabled = true) }
                                    // Persist consent and activation together before opening Android settings.
                                    val saved = repository.state.value
                                    if (saved.ready && !saved.error && saved.settings.consent && saved.settings.enabled) {
                                        setupDismissed = true
                                        page = Page.HOME
                                        openAccessibility()
                                    } else snackbar.showSnackbar(saveFailure)
                                } catch (_: java.io.IOException) {
                                    snackbar.showSnackbar(saveFailure)
                                } finally { busy = false }
                            }
                        },
                        onDecline = { setupDismissed = true; page = Page.HOME },
                    )
                    page == Page.SETTINGS -> SettingsPage(
                        settings = state.settings,
                        onPermission = ::setupOrOpenAccessibility,
                        onRevoke = {
                            scope.launch {
                                try {
                                    repository.revokeConsent()
                                    setupDismissed = true
                                    page = Page.HOME
                                } catch (_: java.io.IOException) { snackbar.showSnackbar(saveFailure) }
                            }
                        },
                    )
                    else -> Dashboard(
                        settings = state.settings, usedMs = state.usageTodayMs,
                        service = service, permission = enabledInAndroid, storageError = state.error,
                        onEnabled = { enabled ->
                            if (enabled && !state.settings.consent) page = Page.SETUP
                            else save { it.copy(enabled = enabled) }
                        },
                        onMode = { mode -> save { it.copy(mode = mode) } },
                        onLimit = { limit -> save { it.copy(dailyLimitMinutes = limit) } },
                        onCover = { cover -> save { it.copy(hideButton = cover) } },
                        onSetup = ::setupOrOpenAccessibility,
                    )
                }
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_launcher),
            contentDescription = null,
            modifier = Modifier.size(44.dp),
        )
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun QuickSetup(busy: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.disclosure_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.permission_instructions), style = MaterialTheme.typography.bodyLarge)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp)) {
        Text(stringResource(R.string.disclosure_intro), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onAccept, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.agree_open_settings), style = MaterialTheme.typography.bodyLarge)
        }
        TextButton(onClick = onDecline, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.decline), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun Dashboard(
    settings: Settings, usedMs: Long, service: ServiceStatus, permission: Boolean, storageError: Boolean,
    onEnabled: (Boolean) -> Unit, onMode: (Mode) -> Unit, onLimit: (Int) -> Unit, onCover: (Boolean) -> Unit,
    onSetup: () -> Unit,
) {
    val status = when {
        storageError || service.storageError -> R.string.status_storage_error
        !settings.enabled || !settings.consent -> R.string.status_off
        !permission -> R.string.status_permission_needed
        !service.connected -> R.string.status_disconnected
        service.overlayError -> R.string.status_overlay_error
        service.navigationFailed -> R.string.status_navigation_failed
        service.detection == "paused" -> R.string.status_paused
        else -> R.string.status_ready
    }
    val protectionLabel = stringResource(R.string.protection)
    val healthy = settings.enabled && settings.consent && permission && service.connected && !storageError && !service.storageError && !service.overlayError && !service.navigationFailed && service.detection != "paused"
    Card(colors = CardDefaults.cardColors(containerColor = if (healthy) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.protection), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(status), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Switch(checked = settings.enabled, onCheckedChange = onEnabled, modifier = Modifier.semantics { contentDescription = protectionLabel })
            }
            if (service.storageError) Text(stringResource(R.string.storage_error_body), style = MaterialTheme.typography.bodyLarge)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModeOption(Mode.BLOCK_ALL, settings.mode, R.string.mode_block, onMode)
        ModeOption(Mode.DAILY_LIMIT, settings.mode, R.string.mode_limit, onMode)
    }
    if (settings.mode == Mode.DAILY_LIMIT) {
        LimitCard(settings.dailyLimitMinutes, usedMs, onLimit)
    } else {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.used_today), style = MaterialTheme.typography.bodyLarge)
                Text(duration(usedMs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            SwitchRow(stringResource(R.string.cover_title), settings.hideButton, onCover)
        }
    }
    OutlinedButton(onClick = onSetup, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(stringResource(R.string.open_accessibility), style = MaterialTheme.typography.bodyLarge)
    }
    Text(stringResource(R.string.language_limitations), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ModeOption(mode: Mode, selected: Mode, title: Int, onMode: (Mode) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().selectable(selected = mode == selected, role = Role.RadioButton, onClick = { onMode(mode) }),
        shape = RoundedCornerShape(24.dp),
        color = if (mode == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.heightIn(min = 72.dp).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = mode == selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun LimitCard(limit: Int, usedMs: Long, onLimit: (Int) -> Unit) {
    var draft by remember(limit) { mutableFloatStateOf(limit.toFloat()) }
    val limitMs = limit * 60_000L
    val label = stringResource(R.string.daily_allowance)
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(androidx.compose.ui.res.pluralStringResource(R.plurals.minutes_value, draft.roundToInt(), draft.roundToInt()), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = draft, onValueChange = { draft = it.roundToInt().toFloat() }, valueRange = 1f..120f,
                onValueChangeFinished = { onLimit(draft.roundToInt()) },
                modifier = Modifier.semantics { contentDescription = label },
            )
            LinearProgressIndicator(progress = { (usedMs.toFloat() / limitMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric(R.string.used_today, duration(usedMs), Modifier.weight(1f))
                Metric(R.string.remaining, duration((limitMs - usedMs).coerceAtLeast(0L)), Modifier.weight(1f))
            }
            Text(stringResource(R.string.reset_midnight), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Metric(label: Int, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun duration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return stringResource(R.string.duration_value, seconds / 60, seconds % 60)
}

@Composable
private fun SettingsPage(settings: Settings, onPermission: () -> Unit, onRevoke: () -> Unit) {
    var showRevoke by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.appearance_system), color = MaterialTheme.colorScheme.onSurfaceVariant)
    InfoCard(R.string.instagram_languages_title, R.string.instagram_languages)
    Text(stringResource(R.string.language_limitations), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = onPermission, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.open_accessibility), style = MaterialTheme.typography.bodyLarge) }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InformationLinks(onPrivacy: () -> Unit, onAbout: () -> Unit, onSettings: (() -> Unit)? = null) {
    FlowRow(
        Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onSettings != null) TextButton(onClick = onSettings) { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.bodyLarge) }
        TextButton(onClick = onPrivacy) { Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.bodyLarge) }
        TextButton(onClick = onAbout) { Text(stringResource(R.string.about_title), style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontSize = 18.sp)
        Switch(checked = checked, onCheckedChange = onChecked, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun InfoCard(title: Int, body: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun InformationPage(title: Int, paragraphs: List<Int>) {
    Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    paragraphs.forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyLarge) }
}
