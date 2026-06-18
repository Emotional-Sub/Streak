package com.streak.app.util

import java.time.LocalDate

object StreakUtils {
    fun today(): String = LocalDate.now().toString()

    fun computeCurrentStreak(completedDates: List<String>): Int {
        if (completedDates.isEmpty()) return 0
        val completed = completedDates.mapNotNull {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }.sortedDescending()
        if (completed.isEmpty()) return 0

        var cursor = LocalDate.now()
        var streak = 0
        val set = completed.toSet()

        if (cursor !in set && cursor.minusDays(1) !in set) return 0
        if (cursor !in set) {
            cursor = cursor.minusDays(1)
        }

        while (cursor in set) {
            streak += 1
            cursor = cursor.minusDays(1)
        }
        return streak
    }
}
