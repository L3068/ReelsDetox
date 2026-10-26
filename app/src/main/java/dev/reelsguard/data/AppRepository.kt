package dev.reelsguard.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.reelsguard.core.CoverTheme
import dev.reelsguard.core.Mode
import dev.reelsguard.core.Settings
import dev.reelsguard.core.UsageState
import dev.reelsguard.core.usedOn
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

private val Context.guardData by preferencesDataStore("reelsguard")

data class AppState(
    val settings: Settings = Settings(),
    val usageTodayMs: Long = 0,
    val ready: Boolean = false,
    val error: Boolean = false,
    val usage: UsageState = UsageState(),
)

class AppRepository(context: Context, scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val store = appContext.guardData
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    private object Keys {
        val enabled = booleanPreferencesKey("enabled")
        val consent = booleanPreferencesKey("consent_v1")
        val mode = stringPreferencesKey("mode")
        val limit = intPreferencesKey("limit_minutes")
        val hide = booleanPreferencesKey("hide_button")
        val coverTheme = stringPreferencesKey("cover_theme")
        val usage = stringPreferencesKey("usage_v1")
    }

    init {
        scope.launch {
            try {
                store.data.collect { prefs ->
                    publishPreferences(prefs)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { fail() } // Never silently replace corrupt usage with a fresh allowance.
        }
    }

    suspend fun updateSettings(transform: (Settings) -> Settings) {
        write {
            val requested = transform(readSettings(it))
            val settings = requested.copy(
                enabled = requested.enabled && requested.consent,
                dailyLimitMinutes = requested.dailyLimitMinutes.coerceIn(1, 120),
            )
            it[Keys.enabled] = settings.enabled
            it[Keys.consent] = settings.consent
            it[Keys.mode] = settings.mode.name
            it[Keys.limit] = settings.dailyLimitMinutes
            it[Keys.hide] = settings.hideButton
            it[Keys.coverTheme] = settings.coverTheme.name
        }
    }

    suspend fun acceptConsent() = updateSettings { it.copy(consent = true) }
    suspend fun revokeConsent() = updateSettings { it.copy(consent = false, enabled = false) }

    suspend fun saveUsage(usage: UsageState): Boolean = write {
        val existing = UsageCodec.decode(it[Keys.usage])
        it[Keys.usage] = UsageCodec.encode(mergeUsage(existing, usage))
    }

    /** Transfer the departing service's final ledger before its asynchronous disk write.
     * A reconnect in this process must start from this total, not an older durable snapshot.
     */
    internal fun publishUsage(usage: UsageState) {
        mutable.update { current ->
            val merged = mergeUsage(current.usage, usage)
            current.copy(usage = merged, usageTodayMs = today(merged))
        }
    }

    /** Main UI calls on resume/tick; no background loop or disk write is needed for a date change. */
    fun refreshToday() {
        mutable.update { it.copy(usageTodayMs = today(it.usage)) }
    }

    private suspend fun write(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit): Boolean = try {
        val prefs = store.edit { block(it) }
        publishPreferences(prefs)
        true
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { fail(); false }

    private fun publishPreferences(prefs: Preferences) {
        val durable = UsageCodec.decode(prefs[Keys.usage])
        val settings = readSettings(prefs)
        mutable.update { current ->
            // An older queued write/flow emission must not undo a synchronous service handoff.
            val usage = mergeUsage(current.usage, durable)
            AppState(settings, today(usage), true, false, usage)
        }
    }
    private fun mergeUsage(first: UsageState, second: UsageState): UsageState {
        val base = if ((first.logicalEpochMillis ?: Long.MIN_VALUE) >
            (second.logicalEpochMillis ?: Long.MIN_VALUE)) first else second
        val days = (first.days.keys + second.days.keys).associateWith { day ->
            maxOf(first.days[day] ?: 0L, second.days[day] ?: 0L)
        }
        return base.copy(days = days)
    }
    private fun fail() { mutable.update { it.copy(error = true, ready = false) } }
    private fun readSettings(p: Preferences) = Settings(
        enabled = p[Keys.enabled] ?: false,
        consent = p[Keys.consent] ?: false,
        mode = runCatching { Mode.valueOf(p[Keys.mode] ?: "BLOCK_ALL") }.getOrDefault(Mode.BLOCK_ALL),
        dailyLimitMinutes = (p[Keys.limit] ?: 15).coerceIn(1, 120),
        hideButton = p[Keys.hide] ?: true,
        coverTheme = runCatching { CoverTheme.valueOf(p[Keys.coverTheme] ?: "SYSTEM") }.getOrDefault(CoverTheme.SYSTEM),
    )
    private fun today(usage: UsageState): Long = usage.usedOn(Instant.now(), ZoneId.systemDefault(), android.os.SystemClock.elapsedRealtime(), appContext.bootCount())
}

/** Stores only dates, durations and clock anchors. No accessibility content enters storage. */
internal object UsageCodec {
    fun encode(state: UsageState): String = JSONObject().apply {
        put("days", JSONObject(state.days))
        state.logicalEpochMillis?.let { put("logical", it) }
        state.lastWallEpochMillis?.let { put("wall", it) }
        state.lastElapsedMillis?.let { put("elapsed", it) }
        state.zoneId?.let { put("zone", it) }
        state.bootCount?.let { put("boot", it) }
    }.toString()

    fun decode(raw: String?): UsageState {
        if (raw == null) return UsageState()
        val json = JSONObject(raw)
        val days = json.getJSONObject("days")
        val values = days.keys().asSequence().associateWith { key ->
            java.time.LocalDate.parse(key)
            days.getLong(key).also { require(it >= 0) }
        }
        fun optionalLong(key: String) = if (json.has(key)) json.getLong(key) else null
        return UsageState(values, optionalLong("logical"), optionalLong("wall"), optionalLong("elapsed"),
            if (json.has("zone")) json.getString("zone").also { ZoneId.of(it) } else null,
            if (json.has("boot")) json.getInt("boot") else null)
    }
}

/** Public boot generation; missing access falls back to elapsedRealtime decrease detection. */
internal fun Context.bootCount(): Int? = runCatching {
    android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT)
}.getOrNull()
