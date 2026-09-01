package app.syncheroic.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class RecordPlanner(
    private val exerciseTypes: Map<String, Int>,
) {
    fun plan(
        workout: Workout,
        candidates: List<CandidateSession>,
        previous: PreviousSyncState?,
        settings: SyncSettings,
        now: Instant,
        forceSynthesize: Boolean = false,
        synthesisStart: Instant? = null,
    ): PlanAction {
        if (!workout.logged) return PlanAction.Skip(workout.id, "Workout is not logged")
        val resolved = resolveTime(workout, candidates, previous, settings, now, forceSynthesize, synthesisStart)
            ?: return PlanAction.Hold(workout.id, "Awaiting a wearable match")
        if (resolved.skip) return PlanAction.Skip(workout.id, "Matched-session behavior is skip")
        val title = WorkoutRenderer.title(workout)
        val notes = WorkoutRenderer.notes(workout, settings)
        val segments = if (settings.segmentsEnabled) segments(workout, resolved.start, resolved.end) else emptyList()
        val digest = digest(workout.id, title, notes, resolved, segments)
        val record = PlannedRecord(
            workoutId = workout.id,
            date = workout.date,
            title = title,
            notes = notes,
            start = resolved.start,
            end = resolved.end,
            timeSource = resolved.source,
            matchedRecordId = resolved.candidate?.id,
            matchedOriginPackage = resolved.candidate?.originPackage,
            matchConfidence = resolved.candidate?.confidence,
            segments = segments,
            digest = digest,
        )
        if (previous == null) return PlanAction.Insert(record)
        return if (previous.appliedDigest == digest) {
            PlanAction.Unchanged(record)
        } else {
            val id = previous.recordId ?: return PlanAction.Insert(record)
            PlanAction.Update(record, id, previous.recordVersion.coerceAtLeast(0) + 1)
        }
    }

    private fun resolveTime(
        workout: Workout,
        candidates: List<CandidateSession>,
        previous: PreviousSyncState?,
        settings: SyncSettings,
        now: Instant,
        forceSynthesize: Boolean,
        synthesisStart: Instant?,
    ): ResolvedTime? {
        if (workout.serverStart != null && workout.serverEnd != null && workout.serverEnd > workout.serverStart) {
            return ResolvedTime(workout.serverStart, workout.serverEnd, TimeSource.SERVER, null)
        }
        if (previous?.timeSource == TimeSource.SERVER) {
            return ResolvedTime(previous.start, previous.end, TimeSource.SERVER, null)
        }
        val previousCandidate = previous?.matchedRecordId?.let { id -> candidates.firstOrNull { it.id == id } }
        val candidate = previousCandidate ?: chooseCandidate(workout.date, candidates, settings)
        if (settings.matchedSessionBehavior == MatchedSessionBehavior.SKIP && candidate != null) {
            return ResolvedTime(candidate.start, candidate.end, TimeSource.BORROWED, candidate, skip = true)
        }
        if (settings.matchedSessionBehavior == MatchedSessionBehavior.ALIGN && candidate != null) {
            return ResolvedTime(candidate.start, candidate.end, TimeSource.BORROWED, candidate)
        }
        if (previous?.timeSource == TimeSource.BORROWED && candidate == null) {
            return ResolvedTime(previous.start, previous.end, TimeSource.BORROWED, null)
        }
        val dateEnd = workout.date.plusDays(1).atStartOfDay(settings.zoneId).toInstant()
        if (!forceSynthesize && Duration.between(dateEnd, now) < settings.matchGracePeriod && now >= dateEnd) return null
        if (!forceSynthesize && now < dateEnd) return null
        val start = synthesisStart ?: workout.date.atTime(settings.defaultStartTime).atZone(settings.zoneId).toInstant()
        return ResolvedTime(start, start.plus(settings.defaultDuration), TimeSource.SYNTHESIZED, null)
    }

    private fun chooseCandidate(date: LocalDate, candidates: List<CandidateSession>, settings: SyncSettings): CandidateSession? {
        val start = date.atStartOfDay(settings.zoneId).toInstant()
        val end = date.plusDays(1).atTime(LocalTime.of(6, 0)).atZone(settings.zoneId).toInstant()
        return candidates.asSequence()
            .filter { it.start >= start && it.start < end && it.duration >= Duration.ofMinutes(20) }
            .sortedWith(compareByDescending<CandidateSession> { it.duration }.thenBy { it.id })
            .firstOrNull()
    }

    private fun segments(workout: Workout, start: Instant, end: Instant): List<PlannedSegment> {
        val mapped = workout.blocks.flatMap { block -> block.exercises.map { block to it } }
            .filter { (_, exercise) -> exercise.performedSets.isNotEmpty() && exerciseTypes.containsKey(normalize(exercise.name)) }
        if (mapped.isEmpty()) return emptyList()
        val totalMillis = Duration.between(start, end).toMillis()
        return mapped.mapIndexed { index, (_, exercise) ->
            val segmentStart = start.plusMillis(totalMillis * index / mapped.size)
            val segmentEnd = start.plusMillis(totalMillis * (index + 1) / mapped.size)
            PlannedSegment(
                name = exercise.name,
                type = exerciseTypes.getValue(normalize(exercise.name)),
                start = segmentStart,
                end = segmentEnd,
                repetitions = exercise.performedSets.sumOf { PerformedValueParser.repetitionsAsInt(it.value) ?: 0 }.takeIf { it > 0 },
            )
        }
    }

    private fun digest(
        workoutId: String,
        title: String,
        notes: String,
        time: ResolvedTime,
        segments: List<PlannedSegment>,
    ): String {
        val canonical = buildString {
            append(workoutId).append('\u0000').append(title).append('\u0000').append(notes).append('\u0000')
            append(time.start).append('\u0000').append(time.end).append('\u0000').append(time.source)
            append('\u0000').append(time.candidate?.id.orEmpty()).append('\u0000').append(time.candidate?.originPackage.orEmpty())
            segments.forEach { append('\u0000').append(it.name).append('|').append(it.type).append('|').append(it.start).append('|').append(it.end).append('|').append(it.repetitions) }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun normalize(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private data class ResolvedTime(
        val start: Instant,
        val end: Instant,
        val source: TimeSource,
        val candidate: CandidateSession?,
        val skip: Boolean = false,
    )
}
