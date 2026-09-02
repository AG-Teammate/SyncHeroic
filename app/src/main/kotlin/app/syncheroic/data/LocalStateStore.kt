package app.syncheroic.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.syncheroic.core.MatchedSessionBehavior
import app.syncheroic.core.PreviousSyncState
import app.syncheroic.core.SyncSettings
import app.syncheroic.core.TimeSource
import app.syncheroic.core.WeightUnit
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("sync_state")

@Serializable
data class LedgerEntry(
    val workoutId: String,
    val recordId: String? = null,
    val recordVersion: Long,
    val appliedDigest: String,
    val start: String,
    val end: String,
    val timeSource: String,
    val matchedRecordId: String? = null,
    val matchedOriginPackage: String? = null,
    val status: String,
    val lastErrorCategory: String? = null,
) {
    fun previous() = PreviousSyncState(
        recordId, recordVersion, appliedDigest, Instant.parse(start), Instant.parse(end),
        runCatching { TimeSource.valueOf(timeSource) }.getOrNull(), matchedRecordId, matchedOriginPackage,
    )
}

data class FrequentSyncSettings(
    val enabled: Boolean = false,
    val start: LocalTime = LocalTime.of(12, 0),
    val end: LocalTime = LocalTime.of(13, 30),
)

class LocalStateStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: Flow<SyncSettings> = context.dataStore.data.map { values ->
        SyncSettings(
            zoneId = ZoneId.systemDefault(),
            defaultStartTime = runCatching { LocalTime.parse(values[START_TIME] ?: "17:00") }.getOrDefault(LocalTime.of(17, 0)),
            defaultDuration = Duration.ofMinutes((values[DURATION_MINUTES] ?: 60).toLong()),
            matchGracePeriod = Duration.ofHours((values[GRACE_HOURS] ?: 48).toLong()),
            matchedSessionBehavior = runCatching { MatchedSessionBehavior.valueOf(values[MATCH_BEHAVIOR] ?: "ALIGN") }.getOrDefault(MatchedSessionBehavior.ALIGN),
            segmentsEnabled = values[SEGMENTS] ?: true,
            notesCap = values[NOTES_CAP] ?: 8_000,
            displayWeightUnit = runCatching { WeightUnit.valueOf(values[WEIGHT_UNIT] ?: "KILOGRAMS") }.getOrDefault(WeightUnit.KILOGRAMS),
        )
    }

    suspend fun ledger(): Map<String, LedgerEntry> {
        val encoded = context.dataStore.data.first()[LEDGER] ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, LedgerEntry>>(encoded) }.getOrDefault(emptyMap())
    }

    suspend fun put(entry: LedgerEntry) {
        val updated = ledger().toMutableMap().apply { put(entry.workoutId, entry) }
        context.dataStore.edit { values ->
            values[LEDGER] = json.encodeToString(updated)
        }
    }

    suspend fun setLastSuccess(instant: Instant, driftCount: Int) {
        context.dataStore.edit { values ->
            values[LAST_SUCCESS] = instant.toEpochMilli()
            values[DRIFT_COUNT] = driftCount
        }
    }

    suspend fun summary(): StateSummary {
        val values = context.dataStore.data.first()
        return StateSummary(values[LAST_SUCCESS]?.let(Instant::ofEpochMilli), values[DRIFT_COUNT] ?: 0, ledger().values.toList())
    }

    suspend fun setRemoteConfig(enabled: Boolean) { context.dataStore.edit { it[REMOTE_CONFIG] = enabled } }
    suspend fun remoteConfigEnabled(): Boolean = context.dataStore.data.first()[REMOTE_CONFIG] ?: false
    suspend fun frequentSyncSettings(): FrequentSyncSettings {
        val values = context.dataStore.data.first()
        return FrequentSyncSettings(
            enabled = values[FREQUENT_SYNC_ENABLED] ?: false,
            start = parseTime(values[FREQUENT_SYNC_START], LocalTime.of(12, 0)),
            end = parseTime(values[FREQUENT_SYNC_END], LocalTime.of(13, 30)),
        )
    }

    suspend fun setFrequentSync(enabled: Boolean, start: LocalTime, end: LocalTime) {
        require(end.isAfter(start)) { "Frequent sync end must be after its start" }
        require(Duration.between(start, end) >= Duration.ofMinutes(15)) { "Frequent sync window must be at least 15 minutes" }
        context.dataStore.edit { values ->
            values[FREQUENT_SYNC_ENABLED] = enabled
            values[FREQUENT_SYNC_START] = start.toString()
            values[FREQUENT_SYNC_END] = end.toString()
        }
    }

    suspend fun markAutomaticAttempt(instant: Instant) {
        context.dataStore.edit { it[LAST_AUTOMATIC_ATTEMPT] = instant.toEpochMilli() }
    }

    suspend fun lastAutomaticAttempt(): Instant? = context.dataStore.data.first()[LAST_AUTOMATIC_ATTEMPT]?.let(Instant::ofEpochMilli)
    suspend fun updateSettings(
        startTime: LocalTime,
        durationMinutes: Int,
        graceHours: Int,
        behavior: MatchedSessionBehavior,
        segments: Boolean,
        notesCap: Int,
        weightUnit: WeightUnit,
    ) {
        context.dataStore.edit { values ->
            values[START_TIME] = startTime.toString()
            values[DURATION_MINUTES] = durationMinutes.coerceIn(20, 360)
            values[GRACE_HOURS] = graceHours.coerceIn(0, 168)
            values[MATCH_BEHAVIOR] = behavior.name
            values[SEGMENTS] = segments
            values[NOTES_CAP] = notesCap.coerceIn(500, 8_000)
            values[WEIGHT_UNIT] = weightUnit.name
        }
    }
    suspend fun clear() { context.dataStore.edit { it.clear() } }

    data class StateSummary(val lastSuccess: Instant?, val driftCount: Int, val entries: List<LedgerEntry>)

    private companion object {
        val START_TIME = stringPreferencesKey("default_start_time")
        val DURATION_MINUTES = intPreferencesKey("default_duration_minutes")
        val GRACE_HOURS = intPreferencesKey("grace_hours")
        val MATCH_BEHAVIOR = stringPreferencesKey("match_behavior")
        val SEGMENTS = booleanPreferencesKey("segments")
        val NOTES_CAP = intPreferencesKey("notes_cap")
        val WEIGHT_UNIT = stringPreferencesKey("weight_unit")
        val REMOTE_CONFIG = booleanPreferencesKey("remote_config")
        val FREQUENT_SYNC_ENABLED = booleanPreferencesKey("frequent_sync_enabled")
        val FREQUENT_SYNC_START = stringPreferencesKey("frequent_sync_start")
        val FREQUENT_SYNC_END = stringPreferencesKey("frequent_sync_end")
        val LAST_AUTOMATIC_ATTEMPT = longPreferencesKey("last_automatic_attempt")
        val LAST_SUCCESS = longPreferencesKey("last_success")
        val DRIFT_COUNT = intPreferencesKey("drift_count")
        val LEDGER = stringPreferencesKey("ledger_v1")

        fun parseTime(value: String?, fallback: LocalTime): LocalTime =
            runCatching { LocalTime.parse(value) }.getOrDefault(fallback)
    }
}
