package app.syncheroic.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class Confidence { HIGH, MEDIUM, LOW }

enum class TimeSource { SERVER, BORROWED, HELD, SYNTHESIZED }

enum class MatchedSessionBehavior { ALIGN, SYNTHESIZE_ANYWAY, SKIP }

enum class SyncStatus { WRITTEN, AWAITING_MATCH, UPDATED, SKIPPED, FAILED }

data class PerformedValue(
    val repetitions: Double?,
    val loadKilograms: Double?,
    val raw: String,
    val confidence: Confidence,
)

data class PerformedSet(
    val number: Int?,
    val value: PerformedValue,
)

data class Exercise(
    val id: String?,
    val name: String,
    val units: List<String?>,
    val performedSets: List<PerformedSet>,
)

data class WorkoutBlock(
    val title: String?,
    val text: String?,
    val exercises: List<Exercise>,
)

data class Workout(
    val id: String,
    val date: LocalDate,
    val logged: Boolean,
    val sourceTitle: String?,
    val programName: String?,
    val blocks: List<WorkoutBlock>,
    val serverStart: Instant? = null,
    val serverEnd: Instant? = null,
)

data class DriftReport(
    val unknownPaths: Map<String, Int> = emptyMap(),
    val unparsedPerformedValues: Map<String, Int> = emptyMap(),
    val unmappedExerciseNames: Map<String, Int> = emptyMap(),
    val unresolvedUnitSemantics: Map<String, Int> = emptyMap(),
) {
    val total: Int
        get() = unknownPaths.values.sum() + unparsedPerformedValues.values.sum() +
            unmappedExerciseNames.values.sum() + unresolvedUnitSemantics.values.sum()
}

data class DecodeResult(
    val workouts: List<Workout>,
    val drift: DriftReport,
)

data class CandidateSession(
    val id: String,
    val originPackage: String,
    val title: String?,
    val exerciseType: Int,
    val start: Instant,
    val end: Instant,
    val confidence: Confidence,
) {
    val duration: Duration get() = Duration.between(start, end)
}

data class PreviousSyncState(
    val recordId: String?,
    val recordVersion: Long,
    val appliedDigest: String,
    val start: Instant,
    val end: Instant,
    val timeSource: TimeSource?,
    val matchedRecordId: String?,
    val matchedOriginPackage: String?,
)

data class SyncSettings(
    val zoneId: ZoneId,
    val defaultStartTime: LocalTime = LocalTime.of(17, 0),
    val defaultDuration: Duration = Duration.ofMinutes(60),
    val matchGracePeriod: Duration = Duration.ofHours(48),
    val matchedSessionBehavior: MatchedSessionBehavior = MatchedSessionBehavior.ALIGN,
    val segmentsEnabled: Boolean = true,
    val notesCap: Int = 8_000,
    val displayWeightUnit: WeightUnit = WeightUnit.KILOGRAMS,
)

enum class WeightUnit { KILOGRAMS, POUNDS }

data class PlannedSegment(
    val name: String,
    val type: Int,
    val start: Instant,
    val end: Instant,
    val repetitions: Int?,
)

data class PlannedRecord(
    val workoutId: String,
    val date: LocalDate,
    val title: String,
    val notes: String,
    val start: Instant,
    val end: Instant,
    val timeSource: TimeSource,
    val matchedRecordId: String?,
    val matchedOriginPackage: String?,
    val matchConfidence: Confidence?,
    val segments: List<PlannedSegment>,
    val digest: String,
)

sealed interface PlanAction {
    val workoutId: String

    data class Hold(override val workoutId: String, val reason: String) : PlanAction
    data class Skip(override val workoutId: String, val reason: String) : PlanAction
    data class Insert(val record: PlannedRecord) : PlanAction {
        override val workoutId: String = record.workoutId
    }
    data class Update(
        val record: PlannedRecord,
        val existingRecordId: String,
        val nextVersion: Long,
    ) : PlanAction {
        override val workoutId: String = record.workoutId
    }
    data class Unchanged(val record: PlannedRecord) : PlanAction {
        override val workoutId: String = record.workoutId
    }
}

