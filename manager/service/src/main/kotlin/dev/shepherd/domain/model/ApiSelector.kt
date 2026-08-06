package dev.shepherd.domain.model

import java.util.*

sealed interface ApiSelector {
    val rawValue: String?

    fun matches(apiLevel: String?): Boolean

    fun matchingLevels(levels: Collection<String>): List<String> {
        return levels
            .filter { level -> matches(level) }
            .distinct()
            .sortedBy { level -> level.toIntOrNull() ?: Int.MAX_VALUE }
    }

    companion object {
        fun parse(rawValue: String?): ApiSelector {
            val normalizedValue: String? = rawValue
                ?.trim()
                ?.takeIf { value -> value.isNotEmpty() }
            if (normalizedValue == null) {
                return AnyLevel
            }

            val listValues: List<String> = normalizedValue
                .split(',')
                .map { value -> value.trim() }
                .filter { value -> value.isNotEmpty() }
            if (listValues.size > 1) {
                val exactLevels: Set<Int> = listValues.map { value -> parseExactLevel(value) }.toSet()
                return LevelSet(rawValue = normalizedValue, exactLevels = exactLevels)
            }

            val value: String = normalizedValue.lowercase(Locale.ROOT)
            if (value.endsWith("+")) {
                val minLevel: Int = parseExactLevel(value.removeSuffix("+"))
                return GreaterThanOrEqual(rawValue = normalizedValue, minLevel = minLevel)
            }
            if (value.startsWith(">=")) {
                val minLevel: Int = parseExactLevel(value.removePrefix(">="))
                return GreaterThanOrEqual(rawValue = normalizedValue, minLevel = minLevel)
            }
            if (value.startsWith("<=")) {
                val maxLevel: Int = parseExactLevel(value.removePrefix("<="))
                return LessThanOrEqual(rawValue = normalizedValue, maxLevel = maxLevel)
            }
            if (value.startsWith(">")) {
                val minExclusiveLevel: Int = parseExactLevel(value.removePrefix(">"))
                return GreaterThan(rawValue = normalizedValue, minExclusiveLevel = minExclusiveLevel)
            }
            if (value.startsWith("<")) {
                val maxExclusiveLevel: Int = parseExactLevel(value.removePrefix("<"))
                return LessThan(rawValue = normalizedValue, maxExclusiveLevel = maxExclusiveLevel)
            }
            if (value.contains("..")) {
                val (fromValue, toValue) = value.split("..", limit = 2)
                val minLevel: Int = parseExactLevel(fromValue)
                val maxLevel: Int = parseExactLevel(toValue)
                require(minLevel <= maxLevel) {
                    "Invalid api selector '$normalizedValue': range start must be <= range end"
                }
                return Range(rawValue = normalizedValue, minLevel = minLevel, maxLevel = maxLevel)
            }
            return ExactLevel(rawValue = normalizedValue, level = parseExactLevel(value))
        }

        private fun parseExactLevel(value: String): Int {
            return value.trim().toIntOrNull()
                ?: throw IllegalArgumentException(
                    "Unsupported api selector '$value'. Use formats like '34', '>=34', '<34', '34+', '33..35', '33,34,35'"
                )
        }
    }
}

data object AnyLevel : ApiSelector {
    override val rawValue: String? = null

    override fun matches(apiLevel: String?): Boolean {
        return apiLevel?.toIntOrNull() != null
    }
}

data class ExactLevel(
    override val rawValue: String,
    val level: Int
) : ApiSelector {
    override fun matches(apiLevel: String?): Boolean {
        return apiLevel?.toIntOrNull() == level
    }
}

data class GreaterThanOrEqual(
    override val rawValue: String,
    val minLevel: Int
) : ApiSelector {
    override fun matches(apiLevel: String?): Boolean {
        val numericApiLevel: Int = apiLevel?.toIntOrNull() ?: return false
        return numericApiLevel >= minLevel
    }
}

data class GreaterThan(
    override val rawValue: String,
    val minExclusiveLevel: Int
) : ApiSelector {
    override fun matches(apiLevel: String?): Boolean {
        val numericApiLevel: Int = apiLevel?.toIntOrNull() ?: return false
        return numericApiLevel > minExclusiveLevel
    }
}

data class LessThanOrEqual(
    override val rawValue: String,
    val maxLevel: Int
) : ApiSelector {
    override fun matches(apiLevel: String?): Boolean {
        val numericApiLevel: Int = apiLevel?.toIntOrNull() ?: return false
        return numericApiLevel <= maxLevel
    }
}

data class LessThan(
    override val rawValue: String,
    val maxExclusiveLevel: Int
) : ApiSelector {
    override fun matches(apiLevel: String?): Boolean {
        val numericApiLevel: Int = apiLevel?.toIntOrNull() ?: return false
        return numericApiLevel < maxExclusiveLevel
    }
}

data class Range(
    override val rawValue: String,
    val minLevel: Int,
    val maxLevel: Int
) : ApiSelector {
    override fun matches(apiLevel: String?): Boolean {
        val numericApiLevel: Int = apiLevel?.toIntOrNull() ?: return false
        return numericApiLevel in minLevel..maxLevel
    }
}

data class LevelSet(
    override val rawValue: String,
    val exactLevels: Set<Int>
) : ApiSelector {
    override fun matches(apiLevel: String?): Boolean {
        val numericApiLevel: Int = apiLevel?.toIntOrNull() ?: return false
        return numericApiLevel in exactLevels
    }
}
