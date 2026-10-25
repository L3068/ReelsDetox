package dev.reelsguard.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
        val usage = stringPreferencesKey("usage_v1")
    }

    init {
        scope.launch {
            try {
                store.data.collect { prefs ->
                    val usage = UsageCodec.decode(prefs[Keys.usage])
                    mutable.value = AppState(readSettings(prefs), today(usage), true, false, usage)
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
        }
    }

    suspend fun acceptConsent() = updateSettings { it.copy(consent = true) }
    suspend fun revokeConsent() = updateSettings { it.copy(consent = false, enabled = false) }

    suspend fun saveUsage(usage: UsageState): Boolean = write {
        val existing = UsageCodec.decode(it[Keys.usage])
        val base = if ((existing.logicalEpochMillis ?: Long.MIN_VALUE) > (usage.logicalEpochMillis ?: Long.MIN_VALUE)) existing else usage
        val merged = (existing.days.keys + usage.days.keys).associateWith { day ->
            maxOf(existing.days[day] ?: 0L, usage.days[day] ?: 0L)
        }
        it[Keys.usage] = UsageCodec.encode(base.copy(days = merged))
    }

    /** Main UI calls on resume/tick; no background loop or disk write is needed for a date change. */
    fun refreshToday() {
        mutable.value = mutable.value.copy(usageTodayMs = today(mutable.value.usage))
    }

    private suspend fun write(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit): Boolean = try {
        val prefs = store.edit { block(it) }
        val usage = UsageCodec.decode(prefs[Keys.usage])
        mutable.value = AppState(readSettings(prefs), today(usage), true, false, usage)
        true
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { fail(); false }

    private fun fail() { mutable.value = mutable.value.copy(error = true, ready = false) }
    private fun readSettings(p: Preferences) = Settings(
        enabled = p[Keys.enabled] ?: false,
        consent = p[Keys.consent] ?: false,
        mode = runCatching { Mode.valueOf(p[Keys.mode] ?: "BLOCK_ALL") }.getOrDefault(Mode.BLOCK_ALL),
        dailyLimitMinutes = (p[Keys.limit] ?: 15).coerceIn(1, 120),
        hideButton = p[Keys.hide] ?: true,
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
