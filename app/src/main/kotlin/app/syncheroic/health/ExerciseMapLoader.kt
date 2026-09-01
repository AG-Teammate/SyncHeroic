package app.syncheroic.health

import androidx.health.connect.client.records.ExerciseSegment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ExerciseMapConfig(val schemaVersion: Int, val exercises: Map<String, String>)

object ExerciseMapLoader {
    fun loadBundled(): Map<String, Int> {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("exercise-map.json"))
        return parse(stream.bufferedReader().use { it.readText() })
    }

    fun parse(payload: String): Map<String, Int> {
        val config = Json.decodeFromString(ExerciseMapConfig.serializer(), payload)
        require(config.schemaVersion == 1)
        require(config.exercises.size <= 2_000)
        return config.exercises.mapValues { (_, semantic) -> semanticTypes.getValue(semantic) }
    }

    private val semanticTypes = mapOf(
        "squat" to ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
        "deadlift" to ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT,
        "bench_press" to ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
        "pull_up" to ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP,
        "barbell_shoulder_press" to ExerciseSegment.EXERCISE_SEGMENT_TYPE_BARBELL_SHOULDER_PRESS,
        "shoulder_press" to ExerciseSegment.EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS,
        "kettlebell_swing" to ExerciseSegment.EXERCISE_SEGMENT_TYPE_KETTLEBELL_SWING,
        "burpee" to ExerciseSegment.EXERCISE_SEGMENT_TYPE_BURPEE,
        "lunge" to ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE,
    )
}
