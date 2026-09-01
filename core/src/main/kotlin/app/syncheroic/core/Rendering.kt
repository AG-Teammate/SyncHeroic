package app.syncheroic.core

import java.math.RoundingMode

object WorkoutRenderer {
    private val genericTitles = setOf("circuit", "workout", "training", "session", "main")

    fun title(workout: Workout): String {
        val performedNames = workout.blocks.flatMap { it.exercises }
            .filter { it.performedSets.isNotEmpty() }
            .map { it.name.trim() }
            .filter(String::isNotEmpty)
            .distinct()
            .take(3)
        if (performedNames.isNotEmpty()) return performedNames.joinToString(", ")
        val blockTitle = workout.blocks.asSequence().mapNotNull { it.title?.trim() }
            .firstOrNull { it.isNotEmpty() && it.lowercase() !in genericTitles }
        return blockTitle ?: workout.programName?.takeIf { it.isNotBlank() } ?: "Strength training"
    }

    fun notes(workout: Workout, settings: SyncSettings): String {
        val lifts = workout.blocks.flatMap { it.exercises }.filter { it.performedSets.isNotEmpty() }.map { exercise ->
            "${exercise.name} — ${renderSets(exercise, settings.displayWeightUnit)}"
        }
        val prose = workout.blocks.mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }
        val complete = buildList {
            addAll(lifts)
            if (lifts.isNotEmpty() && prose.isNotEmpty()) add("---")
            addAll(prose)
        }.joinToString("\n")
        if (complete.length <= settings.notesCap) return complete
        val marker = "\n… [truncated by SyncHeroic]"
        return complete.take((settings.notesCap - marker.length).coerceAtLeast(0)) + marker.take(settings.notesCap)
    }

    private fun renderSets(exercise: Exercise, unit: WeightUnit): String {
        val values = exercise.performedSets.map { it.value }
        if (values.all { it.repetitions != null } && values.map { it.repetitions }.distinct().size == 1) {
            val repetitions = format(values.first().repetitions!!)
            val loads = values.map { it.loadKilograms }
            if (loads.all { it != null }) {
                return "${values.size}x$repetitions @ ${loads.joinToString("/") { formatWeight(it!!, unit) }} ${unit.label}"
            }
            if (loads.all { it == null }) return "${values.size}x$repetitions"
        }
        return values.joinToString(" / ") { value ->
            if (value.repetitions == null) value.raw else buildString {
                append(format(value.repetitions))
                value.loadKilograms?.let { append(" @ ${formatWeight(it, unit)} ${unit.label}") }
            }
        }
    }

    private fun formatWeight(kilograms: Double, unit: WeightUnit): String = format(
        if (unit == WeightUnit.POUNDS) kilograms / 0.45359237 else kilograms,
    )
    private fun format(value: Double): String = value.toBigDecimal().setScale(2, RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()
    private val WeightUnit.label: String get() = if (this == WeightUnit.POUNDS) "lb" else "kg"
}

