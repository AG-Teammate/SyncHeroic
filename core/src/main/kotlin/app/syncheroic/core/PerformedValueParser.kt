package app.syncheroic.core

import kotlin.math.roundToInt

object PerformedValueParser {
    private val number = Regex("^[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)$")
    private val pair = Regex("^([+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))\\s*@\\s*([+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))$")
    private val prescribed = listOf(
        Regex("^\\d+:\\d+$", RegexOption.IGNORE_CASE),
        Regex("^MAX$", RegexOption.IGNORE_CASE),
        Regex("^[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)\\s*@\\s*(?:-|MAX)$", RegexOption.IGNORE_CASE),
    )

    fun parsePerformed(rawValue: String, units: List<String?> = emptyList()): PerformedValue? {
        val raw = rawValue.trim()
        if (number.matches(raw)) {
            return PerformedValue(raw.toDouble(), null, rawValue, Confidence.HIGH)
        }
        val match = pair.matchEntire(raw) ?: return null
        val first = match.groupValues[1].toDouble()
        val second = match.groupValues[2].toDouble()
        val secondUnit = units.getOrNull(1)?.lowercase().orEmpty()
        val firstUnit = units.getOrNull(0)?.lowercase().orEmpty()
        val loadIsFirst = firstUnit.isWeight() && !secondUnit.isWeight()
        val loadPounds = when {
            loadIsFirst -> firstUnit.isPounds()
            else -> secondUnit.isPounds()
        }
        val repetitions = if (loadIsFirst) second else first
        val load = if (loadIsFirst) first else second
        val confidence = if (firstUnit.isWeight() || secondUnit.isWeight()) Confidence.MEDIUM else Confidence.LOW
        return PerformedValue(
            repetitions = repetitions,
            loadKilograms = if (loadPounds) load * 0.45359237 else load,
            raw = rawValue,
            confidence = confidence,
        )
    }

    fun acceptsPrescription(rawValue: String): Boolean {
        val raw = rawValue.trim()
        return number.matches(raw) || pair.matches(raw) || prescribed.any { it.matches(raw) }
    }

    fun repetitionsAsInt(value: PerformedValue): Int? = value.repetitions
        ?.takeIf { it >= 0.0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }
        ?.roundToInt()

    private fun String.isWeight(): Boolean = contains("kg") || contains("lb") || contains("weight") || contains("load")
    private fun String.isPounds(): Boolean = contains("lb") || contains("pound")
}

