package app.syncheroic.core

import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

class WorkoutDecoder(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    fun decode(payload: String): DecodeResult {
        val root = json.parseToJsonElement(payload)
        val workoutElements = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["workouts"] ?: root["data"] ?: JsonArray(emptyList())).asArray()
            else -> JsonArray(emptyList())
        }
        val unknown = linkedMapOf<String, Int>()
        val unparsed = linkedMapOf<String, Int>()
        val unresolved = linkedMapOf<String, Int>()
        val workouts = workoutElements.mapNotNull { element ->
            decodeWorkout(element.asObject(), unknown, unparsed, unresolved)
        }
        return DecodeResult(
            workouts = workouts,
            drift = DriftReport(
                unknownPaths = unknown.toSortedMap(),
                unparsedPerformedValues = unparsed.toSortedMap(),
                unresolvedUnitSemantics = unresolved.toSortedMap(),
            ),
        )
    }

    private fun decodeWorkout(
        obj: JsonObject,
        unknown: MutableMap<String, Int>,
        unparsed: MutableMap<String, Int>,
        unresolved: MutableMap<String, Int>,
    ): Workout? {
        if (obj["summarizedSavedWorkout"] is JsonObject) {
            return decodeRangeWorkout(obj, unknown, unparsed, unresolved)
        }
        collectUnknown(obj, WORKOUT_KEYS, "workouts[]", unknown)
        val id = obj.string("id", "workoutId", "workout_id") ?: return null
        val date = obj.string("date", "workoutDate", "workout_date")?.let(::parseDate) ?: return null
        val blocks = obj.array("blocks", "workoutBlocks", "workout_blocks").map { blockElement ->
            decodeBlock(blockElement.asObject(), unknown, unparsed, unresolved)
        }
        return Workout(
            id = id,
            date = date,
            logged = obj.boolean("logged", "isLogged", "is_logged") ?: false,
            sourceTitle = obj.string("title", "name"),
            programName = obj.string("programName", "program_name", "program"),
            blocks = blocks,
            serverStart = obj.string("startTime", "start_time", "completedAt")?.let(::parseInstant),
            serverEnd = obj.string("endTime", "end_time")?.let(::parseInstant),
        )
    }

    private fun decodeRangeWorkout(
        obj: JsonObject,
        unknown: MutableMap<String, Int>,
        unparsed: MutableMap<String, Int>,
        unresolved: MutableMap<String, Int>,
    ): Workout? {
        collectUnknown(obj, RANGE_WORKOUT_KEYS, "workouts[]", unknown)
        val id = obj.string("id") ?: return null
        val date = obj.string("date")?.let(::parseDate) ?: return null
        val summary = obj.objectValue("summarizedSavedWorkout")
        val prescription = summary.objectValue("workout")
        val saved = summary.objectValue("saved_workout")
        val savedSets = (saved.array("workoutSets") + saved.array("addedWorkoutSets"))
            .map { it.asObject() }
        val performedByTemplateId = linkedMapOf<String, JsonObject>()
        savedSets.flatMap { it.array("workoutSetExercises") }.map { it.asObject() }.forEach { exercise ->
            exercise.string("workout_set_exercise_id")?.let { performedByTemplateId[it] = exercise }
        }
        val blocks = prescription.array("workoutSets").map { it.asObject() }.map { block ->
            val exercises = block.array("workoutSetExercises").map { it.asObject() }.map { prescribed ->
                val performed = prescribed.string("id")?.let(performedByTemplateId::get)
                decodeRangeExercise(prescribed, performed, unparsed, unresolved, false)
            }
            WorkoutBlock(
                title = block.string("title"),
                text = block.string("instruction")?.takeIf(String::isNotBlank),
                exercises = exercises,
            )
        }.toMutableList()
        savedSets.forEach { savedBlock ->
            val extra = savedBlock.array("workoutSetExercises").map { it.asObject() }
                .filter { exercise ->
                    val templateId = exercise.string("workout_set_exercise_id")
                    templateId == null || templateId !in performedByTemplateId || prescription.array("workoutSets")
                        .flatMap { it.asObject().array("workoutSetExercises") }
                        .none { it.asObject().string("id") == templateId }
                }
                .map { decodeRangeExercise(it, it, unparsed, unresolved, true) }
                .filter { it.performedSets.isNotEmpty() }
            if (extra.isNotEmpty()) {
                blocks += WorkoutBlock(savedBlock.string("title"), savedBlock.string("instruction"), extra)
            }
        }
        val sessionNotes = saved.string("notes") ?: prescription.string("instruction")
        if (!sessionNotes.isNullOrBlank() && blocks.none { it.text == sessionNotes }) {
            blocks += WorkoutBlock(null, sessionNotes, emptyList())
        }
        val rawStart = saved.epoch("timestamp_started")
        val rawEnd = saved.epoch("timestamp_completed")
        val serverWindow = if (
            rawStart != null && rawEnd != null && rawEnd > rawStart &&
            Duration.between(rawStart, rawEnd) <= Duration.ofHours(24)
        ) rawStart to rawEnd else null
        return Workout(
            id = id,
            date = date,
            logged = blocks.any { block -> block.exercises.any { it.performedSets.isNotEmpty() } },
            sourceTitle = obj.string("workout_title"),
            programName = obj.string("program_title", "team_title"),
            blocks = blocks,
            serverStart = serverWindow?.first,
            serverEnd = serverWindow?.second,
        )
    }

    private fun decodeRangeExercise(
        prescribed: JsonObject,
        performed: JsonObject?,
        unparsed: MutableMap<String, Int>,
        unresolved: MutableMap<String, Int>,
        savedOnly: Boolean,
    ): Exercise {
        val source = if (savedOnly) performed ?: prescribed else prescribed
        val units = listOf(source.int("param_1_type")?.unitLabel(), source.int("param_2_type")?.unitLabel())
        val values = performed?.let { exercise ->
            (1..10).mapNotNull { slot ->
                if (exercise.int("param_${slot}_made") != 1) return@mapNotNull null
                val first = exercise.string("param_1_data_$slot")?.takeIf(String::isNotBlank)
                val second = exercise.string("param_2_data_$slot")?.takeIf(String::isNotBlank)
                val raw = when {
                    first != null && second != null -> "$first @ $second"
                    first != null -> first
                    second != null -> "@ $second"
                    else -> return@mapNotNull null
                }
                val parsed = PerformedValueParser.parsePerformed(raw, units)
                if (parsed == null) {
                    unparsed.increment("workouts[].blocks[].exercises[].sets[].performed")
                    PerformedSet(slot, PerformedValue(null, null, raw, Confidence.LOW))
                } else {
                    val name = source.string("title", "exercise_title") ?: "Unnamed exercise"
                    if (parsed.loadKilograms != null && parsed.confidence == Confidence.LOW) unresolved.increment(name)
                    PerformedSet(slot, parsed)
                }
            }
        }.orEmpty()
        return Exercise(
            id = source.string("exercise_id"),
            name = source.string("title", "exercise_title") ?: "Unnamed exercise",
            units = units,
            performedSets = values,
        )
    }

    private fun decodeBlock(
        obj: JsonObject,
        unknown: MutableMap<String, Int>,
        unparsed: MutableMap<String, Int>,
        unresolved: MutableMap<String, Int>,
    ): WorkoutBlock {
        collectUnknown(obj, BLOCK_KEYS, "workouts[].blocks[]", unknown)
        return WorkoutBlock(
            title = obj.string("title", "name"),
            text = obj.string("text", "instructions", "description", "notes"),
            exercises = obj.array("exercises", "items").map { exerciseElement ->
                decodeExercise(exerciseElement.asObject(), unknown, unparsed, unresolved)
            },
        )
    }

    private fun decodeExercise(
        obj: JsonObject,
        unknown: MutableMap<String, Int>,
        unparsed: MutableMap<String, Int>,
        unresolved: MutableMap<String, Int>,
    ): Exercise {
        collectUnknown(obj, EXERCISE_KEYS, "workouts[].blocks[].exercises[]", unknown)
        val units = obj.array("units", "unit").map { (it as? JsonPrimitive)?.contentOrNull }
        val name = obj.string("name", "title", "exerciseName", "exercise_name") ?: "Unnamed exercise"
        val sets = obj.array("sets", "performed", "performedSets", "performed_sets").mapNotNull { setElement ->
            val setObject = setElement as? JsonObject
            val raw = when (setElement) {
                is JsonPrimitive -> setElement.contentOrNull
                is JsonObject -> setElement.string("performed", "value", "result")
                else -> null
            } ?: return@mapNotNull null
            val parsed = PerformedValueParser.parsePerformed(raw, units)
            if (parsed == null) {
                unparsed.increment("workouts[].blocks[].exercises[].sets[].value")
                PerformedSet(setObject?.int("number", "set", "index"), PerformedValue(null, null, raw, Confidence.LOW))
            } else {
                if (parsed.loadKilograms != null && parsed.confidence == Confidence.LOW) unresolved.increment(name)
                PerformedSet(setObject?.int("number", "set", "index"), parsed)
            }
        }
        return Exercise(obj.string("id", "exerciseId", "exercise_id"), name, units, sets)
    }

    private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
    private fun parseInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

    private fun collectUnknown(obj: JsonObject, known: Set<String>, path: String, target: MutableMap<String, Int>) {
        obj.keys.filterNot(known::contains).forEach { target.increment("$path.$it") }
    }

    private fun MutableMap<String, Int>.increment(key: String) { this[key] = getOrDefault(key, 0) + 1 }
    private fun JsonElement.asArray(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
    private fun JsonElement.asObject(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (get(key) as? JsonPrimitive)?.contentOrNull
    }
    private fun JsonObject.boolean(vararg keys: String): Boolean? = keys.firstNotNullOfOrNull { key ->
        (get(key) as? JsonPrimitive)?.booleanOrNull
    }
    private fun JsonObject.int(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
        (get(key) as? JsonPrimitive)?.intOrNull
    }
    private fun JsonObject.array(vararg keys: String): JsonArray = keys.firstNotNullOfOrNull { key ->
        when (val value = get(key)) {
            is JsonArray -> value
            is JsonNull, null -> null
            else -> JsonArray(listOf(value))
        }
    } ?: JsonArray(emptyList())
    private fun JsonObject.objectValue(key: String): JsonObject = get(key) as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.epoch(key: String): Instant? {
        val numeric = (get(key) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return null
        if (!numeric.isFinite() || numeric <= 0) return null
        val milliseconds = if (numeric > 10_000_000_000) numeric.toLong() else (numeric * 1_000).toLong()
        return runCatching { Instant.ofEpochMilli(milliseconds) }.getOrNull()
    }
    private fun Int.unitLabel(): String? = when (this) {
        0 -> null
        1 -> "lb"
        2 -> "%max"
        3 -> "reps"
        4, 18 -> "sec"
        5 -> "yd"
        6 -> "m"
        7, 12 -> "in"
        10 -> "mi"
        11 -> "ft"
        13 -> "bpm"
        14 -> "RPE"
        else -> null
    }

    private companion object {
        val WORKOUT_KEYS = setOf(
            "id", "workoutId", "workout_id", "date", "workoutDate", "workout_date", "logged", "isLogged",
            "is_logged", "title", "name", "programName", "program_name", "program", "blocks", "workoutBlocks",
            "workout_blocks", "startTime", "start_time", "completedAt", "endTime", "end_time",
        )
        val RANGE_WORKOUT_KEYS = setOf(
            "id", "date", "workout_title", "program_title", "team_title", "program_id", "team_id", "personal_cal",
            "summarizedSavedWorkout", "start", "end", "order", "published", "created_at", "updated_at",
        )
        val BLOCK_KEYS = setOf("title", "name", "text", "instructions", "description", "notes", "exercises", "items", "id", "order")
        val EXERCISE_KEYS = setOf(
            "id", "exerciseId", "exercise_id", "name", "title", "exerciseName", "exercise_name", "units", "unit",
            "sets", "performed", "performedSets", "performed_sets", "prescribed", "order",
        )
    }
}
