package app.syncheroic.sync

import app.syncheroic.core.PlanAction
import app.syncheroic.core.DriftReport
import app.syncheroic.core.PreviousSyncState
import app.syncheroic.core.RecordPlanner
import app.syncheroic.core.WorkoutDecoder
import app.syncheroic.data.LedgerEntry
import app.syncheroic.data.LocalStateStore
import app.syncheroic.health.HealthConnectGateway
import app.syncheroic.network.TrainHeroicClient
import app.syncheroic.network.RemoteConfigUpdater
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SyncPreview(
    val actions: List<PlanAction>,
    val drift: DriftReport,
) {
    val driftCount: Int get() = drift.total
    val inserts: Int get() = actions.count { it is PlanAction.Insert }
    val updates: Int get() = actions.count { it is PlanAction.Update }
    val held: Int get() = actions.count { it is PlanAction.Hold }
    val skipped: Int get() = actions.count { it is PlanAction.Skip }
    val unchanged: Int get() = actions.count { it is PlanAction.Unchanged }
}

class SyncCoordinator(
    private val trainHeroic: TrainHeroicClient,
    private val health: HealthConnectGateway,
    private val state: LocalStateStore,
    private val decoder: WorkoutDecoder = WorkoutDecoder(),
    private var planner: RecordPlanner = RecordPlanner(emptyMap()),
    private val remoteConfigUpdater: RemoteConfigUpdater? = null,
) {
    private val automaticSyncMutex = Mutex()

    suspend fun preview(start: LocalDate, end: LocalDate = LocalDate.now(), refreshRemoteConfig: Boolean = true): SyncPreview {
        if (refreshRemoteConfig && state.remoteConfigEnabled()) {
            remoteConfigUpdater?.let { updater ->
                runCatching { updater.fetch() }.getOrNull()?.let { remote ->
                    trainHeroic.replaceEndpoints(remote.endpoints)
                    planner = RecordPlanner(remote.exerciseMap)
                }
            }
        }
        val payload = trainHeroic.fetchWorkouts(start, end)
        val decoded = decoder.decode(payload)
        val settings = state.settings.first()
        val startInstant = start.atStartOfDay(settings.zoneId).toInstant()
        val endInstant = end.plusDays(1).atStartOfDay(settings.zoneId).plusHours(6).toInstant()
        val permissions = health.grantedPermissions()
        val candidates = if (HealthConnectGateway.READ_HISTORY in permissions || start >= LocalDate.now().minusDays(30)) {
            runCatching { health.readCandidates(startInstant, endInstant) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val own = runCatching { health.readOwn(startInstant, endInstant) }.getOrDefault(emptyList()).associateBy { it.clientRecordId }
        val ledger = state.ledger()
        val now = Instant.now()
        val usedCandidates = mutableSetOf<String>()
        val lastEndByDate = mutableMapOf<LocalDate, Instant>()
        val actions = decoded.workouts.sortedWith(compareBy({ it.date }, { it.id })).map { workout ->
            val saved = ledger[workout.id]
            val existing = own[workout.id]
            val previous = when {
                saved != null -> saved.previous().copy(
                    recordId = existing?.id ?: saved.recordId,
                    recordVersion = maxOf(saved.recordVersion, existing?.clientRecordVersion ?: 0),
                )
                existing != null -> PreviousSyncState(
                    recordId = existing.id,
                    recordVersion = existing.clientRecordVersion,
                    appliedDigest = "",
                    start = existing.start,
                    end = existing.end,
                    timeSource = null,
                    matchedRecordId = null,
                    matchedOriginPackage = null,
                )
                else -> null
            }
            planner.plan(
                workout = workout,
                candidates = candidates.filterNot { it.id in usedCandidates },
                previous = previous,
                settings = settings,
                now = now,
                forceSynthesize = lastEndByDate[workout.date] != null,
                synthesisStart = lastEndByDate[workout.date],
            ).also { action ->
                val record = when (action) {
                    is PlanAction.Insert -> action.record
                    is PlanAction.Update -> action.record
                    is PlanAction.Unchanged -> action.record
                    else -> null
                }
                record?.matchedRecordId?.let(usedCandidates::add)
                record?.let { lastEndByDate[workout.date] = it.end }
            }
        }
        return SyncPreview(actions, decoded.drift)
    }

    suspend fun automaticSync(start: LocalDate, end: LocalDate, refreshRemoteConfig: Boolean): Boolean = automaticSyncMutex.withLock {
        val now = Instant.now()
        val lastAttempt = state.lastAutomaticAttempt()
        if (lastAttempt != null && java.time.Duration.between(lastAttempt, now) < AUTOMATIC_SYNC_THROTTLE) return false
        apply(preview(start, end, refreshRemoteConfig))
        state.markAutomaticAttempt(now)
        true
    }

    suspend fun apply(preview: SyncPreview): SyncPreview {
        preview.actions.chunked(100).forEachIndexed { chunkIndex, chunk ->
            chunk.forEach { action ->
                when (action) {
                    is PlanAction.Insert -> write(action.record, 1, "WRITTEN")
                    is PlanAction.Update -> write(action.record, action.nextVersion, "UPDATED")
                    is PlanAction.Hold -> Unit
                    is PlanAction.Skip -> Unit
                    is PlanAction.Unchanged -> Unit
                }
            }
            if (chunkIndex < preview.actions.lastIndex / 100) delay(250)
        }
        state.setLastSuccess(Instant.now(), preview.drift.total)
        return preview
    }

    suspend fun deleteAll(): Int = health.deleteAllOwned()

    private suspend fun write(record: app.syncheroic.core.PlannedRecord, version: Long, status: String) {
        health.upsert(record, version)
        state.put(
            LedgerEntry(
                workoutId = record.workoutId,
                recordVersion = version,
                appliedDigest = record.digest,
                start = record.start.toString(),
                end = record.end.toString(),
                timeSource = record.timeSource.name,
                matchedRecordId = record.matchedRecordId,
                matchedOriginPackage = record.matchedOriginPackage,
                status = status,
            ),
        )
    }

    private companion object {
        val AUTOMATIC_SYNC_THROTTLE: java.time.Duration = java.time.Duration.ofMinutes(10)
    }
}
