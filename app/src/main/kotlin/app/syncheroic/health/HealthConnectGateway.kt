package app.syncheroic.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.syncheroic.core.CandidateSession
import app.syncheroic.core.Confidence
import app.syncheroic.core.PlannedRecord
import java.time.Instant
import java.time.ZoneId

data class OwnHealthRecord(
    val id: String,
    val clientRecordId: String,
    val clientRecordVersion: Long,
    val start: Instant,
    val end: Instant,
)

class HealthConnectGateway(private val context: Context) {
    val availability: Int get() = HealthConnectClient.getSdkStatus(context)
    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    val requiredPermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
    )
    val matchingPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        READ_HISTORY,
        READ_BACKGROUND,
    )

    fun permissionsToRequest(): Set<String> = buildSet {
        addAll(requiredPermissions)
        add(HealthPermission.getReadPermission(ExerciseSessionRecord::class))
        if (client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
            add(READ_HISTORY)
        }
        if (client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
            add(READ_BACKGROUND)
        }
    }

    suspend fun grantedPermissions(): Set<String> = if (availability == HealthConnectClient.SDK_AVAILABLE) {
        client.permissionController.getGrantedPermissions()
    } else {
        emptySet()
    }

    suspend fun readCandidates(start: Instant, end: Instant): List<CandidateSession> {
        val records = readAll(start, end, emptySet()).filter { it.metadata.dataOrigin.packageName != context.packageName }
        return records.map { record ->
            CandidateSession(
                id = record.metadata.id,
                originPackage = record.metadata.dataOrigin.packageName,
                title = record.title,
                exerciseType = record.exerciseType,
                start = record.startTime,
                end = record.endTime,
                confidence = if (record.exerciseType in HIGH_CONFIDENCE_TYPES) Confidence.HIGH else Confidence.MEDIUM,
            )
        }
    }

    suspend fun readOwn(start: Instant, end: Instant): List<OwnHealthRecord> = readAll(
        start, end, setOf(DataOrigin(context.packageName)),
    ).mapNotNull { record ->
        val clientId = record.metadata.clientRecordId ?: return@mapNotNull null
        OwnHealthRecord(
            id = record.metadata.id,
            clientRecordId = clientId,
            clientRecordVersion = record.metadata.clientRecordVersion,
            start = record.startTime,
            end = record.endTime,
        )
    }

    suspend fun upsert(record: PlannedRecord, version: Long) {
        val zone = ZoneId.systemDefault()
        val healthRecord = ExerciseSessionRecord(
            startTime = record.start,
            startZoneOffset = zone.rules.getOffset(record.start),
            endTime = record.end,
            endZoneOffset = zone.rules.getOffset(record.end),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            title = record.title,
            notes = record.notes,
            segments = record.segments.filter { it.type > 0 }.map { segment ->
                ExerciseSegment(
                    startTime = segment.start,
                    endTime = segment.end,
                    segmentType = segment.type,
                    repetitions = segment.repetitions ?: 0,
                )
            },
            metadata = Metadata.manualEntry(
                device = Device(type = Device.TYPE_PHONE),
                clientRecordId = record.workoutId,
                clientRecordVersion = version,
            ),
        )
        client.insertRecords(listOf(healthRecord))
    }

    suspend fun deleteAllOwned(): Int {
        val start = Instant.parse("1970-01-01T00:00:00Z")
        val own = readOwn(start, Instant.now().plusSeconds(86_400))
        own.chunked(100).forEach { chunk ->
            client.deleteRecords(
                ExerciseSessionRecord::class,
                recordIdsList = chunk.map { it.id },
                clientRecordIdsList = emptyList(),
            )
        }
        return own.size
    }

    private suspend fun readAll(
        start: Instant,
        end: Instant,
        origins: Set<DataOrigin>,
    ): List<ExerciseSessionRecord> {
        val output = mutableListOf<ExerciseSessionRecord>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    dataOriginFilter = origins,
                    pageSize = 1_000,
                    pageToken = pageToken,
                ),
            )
            output += response.records
            pageToken = response.pageToken?.takeIf(String::isNotEmpty)
        } while (pageToken != null)
        return output
    }

    companion object {
        const val READ_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"
        const val READ_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

        private val HIGH_CONFIDENCE_TYPES = setOf(
            ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP,
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
            ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT,
        )
    }
}
