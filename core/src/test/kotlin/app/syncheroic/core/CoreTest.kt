package app.syncheroic.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreTest {
    @Test
    fun `performed grammar parses repetitions and load`() {
        val parsed = PerformedValueParser.parsePerformed("5 @ 225", listOf("reps", "lb"))!!
        assertEquals(5.0, parsed.repetitions)
        assertEquals(102.05828325, parsed.loadKilograms!!, 0.000001)
        assertEquals(Confidence.MEDIUM, parsed.confidence)
        assertNull(PerformedValueParser.parsePerformed("five at heavy"))
        assertTrue(PerformedValueParser.acceptsPrescription("5 @ MAX"))
        assertTrue(PerformedValueParser.acceptsPrescription("3:1"))
    }

    @Test
    fun `decoder collects drift and preserves raw performed value`() {
        val result = WorkoutDecoder().decode(resource("/fixtures/shapes.json"))
        assertEquals(2, result.workouts.size)
        assertTrue("workouts[].newServerField" in result.drift.unknownPaths)
        assertEquals(1, result.drift.unparsedPerformedValues.values.sum())
    }

    @Test
    fun `decoder joins performed slots from range endpoint`() {
        val payload = """
            [{
              "id": 91,
              "date": "2025-02-03",
              "workout_title": "2025-02-03",
              "program_title": "Invented Program",
              "summarizedSavedWorkout": {
                "workout": {"workoutSets": [{"title": "Strength", "workoutSetExercises": [
                  {"id": 701, "exercise_id": 8, "title": "Invented Lift", "param_1_type": 3, "param_2_type": 1}
                ]}]},
                "saved_workout": {"timestamp_started": 1738605600, "timestamp_completed": 1738609200, "workoutSets": [{"workoutSetExercises": [
                  {"workout_set_exercise_id": 701, "param_1_data_1": 5, "param_2_data_1": 100, "param_1_made": 1}
                ]}]}
              }
            }]
        """.trimIndent()
        val workout = WorkoutDecoder().decode(payload).workouts.single()
        assertTrue(workout.logged)
        assertEquals("5 @ 100", workout.blocks.single().exercises.single().performedSets.single().value.raw)
        assertEquals(Duration.ofHours(1), Duration.between(workout.serverStart!!, workout.serverEnd!!))
    }

    @Test
    fun `only first workout on a date consumes a candidate`() {
        val settings = SyncSettings(zoneId = ZoneId.of("UTC"))
        val candidate = CandidateSession(
            "watch", "watch.app", "Boot camp", 1,
            Instant.parse("2025-01-01T18:00:00Z"), Instant.parse("2025-01-01T19:00:00Z"), Confidence.HIGH,
        )
        val planner = RecordPlanner(emptyMap())
        val first = planner.plan(workout(LocalDate.of(2025, 1, 1)), listOf(candidate), null, settings, Instant.parse("2026-01-01T00:00:00Z")) as PlanAction.Insert
        val second = planner.plan(
            workout(LocalDate.of(2025, 1, 1)).copy(id = "workout-2"), emptyList(), null, settings,
            Instant.parse("2026-01-01T00:00:00Z"), forceSynthesize = true, synthesisStart = first.record.end,
        ) as PlanAction.Insert
        assertEquals(TimeSource.BORROWED, first.record.timeSource)
        assertEquals(TimeSource.SYNTHESIZED, second.record.timeSource)
        assertEquals(first.record.end, second.record.start)
    }

    @Test
    fun `recent workout is held and old workout is deterministic`() {
        val settings = SyncSettings(zoneId = ZoneId.of("America/New_York"))
        val planner = RecordPlanner(mapOf("invented squat" to 1))
        val recent = workout(LocalDate.of(2026, 8, 31))
        assertInstanceOf(PlanAction.Hold::class.java, planner.plan(recent, emptyList(), null, settings, Instant.parse("2026-09-01T16:00:00Z")))
        val old = workout(LocalDate.of(2025, 1, 1))
        val first = planner.plan(old, emptyList(), null, settings, Instant.parse("2026-09-01T16:00:00Z"))
        val second = planner.plan(old, emptyList(), null, settings, Instant.parse("2026-09-01T16:00:00Z"))
        assertEquals(first, second)
        assertInstanceOf(PlanAction.Insert::class.java, first)
    }

    @Test
    fun `borrowed time survives deleted candidate`() {
        val settings = SyncSettings(zoneId = ZoneId.of("UTC"))
        val prior = PreviousSyncState(
            recordId = "hc", recordVersion = 2, appliedDigest = "old",
            start = Instant.parse("2025-01-01T18:00:00Z"), end = Instant.parse("2025-01-01T19:00:00Z"),
            timeSource = TimeSource.BORROWED, matchedRecordId = "watch", matchedOriginPackage = "watch.app",
        )
        val result = RecordPlanner(emptyMap()).plan(
            workout(LocalDate.of(2025, 1, 1)), emptyList(), prior, settings, Instant.parse("2026-01-01T00:00:00Z"),
        ) as PlanAction.Update
        assertEquals(TimeSource.BORROWED, result.record.timeSource)
        assertEquals(prior.start, result.record.start)
    }

    @Test
    fun `server time survives upstream timestamp removal`() {
        val settings = SyncSettings(zoneId = ZoneId.of("UTC"))
        val prior = PreviousSyncState(
            recordId = "hc", recordVersion = 2, appliedDigest = "old",
            start = Instant.parse("2025-01-01T18:00:00Z"), end = Instant.parse("2025-01-01T19:00:00Z"),
            timeSource = TimeSource.SERVER, matchedRecordId = null, matchedOriginPackage = null,
        )
        val result = RecordPlanner(emptyMap()).plan(
            workout(LocalDate.of(2025, 1, 1)), emptyList(), prior, settings, Instant.parse("2026-01-01T00:00:00Z"),
        ) as PlanAction.Update
        assertEquals(TimeSource.SERVER, result.record.timeSource)
        assertEquals(prior.start, result.record.start)
    }

    private fun workout(date: LocalDate) = Workout(
        id = "workout-1", date = date, logged = true, sourceTitle = date.toString(), programName = "Invented program",
        blocks = listOf(
            WorkoutBlock("Strength", null, listOf(Exercise("e1", "Invented Squat", listOf("reps", "kg"), listOf(PerformedSet(1, PerformedValue(5.0, 50.0, "5 @ 50", Confidence.HIGH)))))),
        ),
    )

    private fun resource(path: String): String = requireNotNull(javaClass.getResource(path)).readText()
}
