package com.streak.app.util

import com.streak.app.data.HabitItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object DashboardUtils {
    private val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    fun categories(): List<String> = listOf("全部", "学习", "运动", "生活", "工作", "阅读")

    fun filterHabits(
        habits: List<HabitItem>,
        query: String,
        category: String
    ): List<HabitItem> {
        val normalizedQuery = query.trim().lowercase()
        return habits.filter { habit ->
            val matchesCategory = category == "全部" || habit.category == category
            val matchesQuery = normalizedQuery.isBlank() ||
                habit.title.lowercase().contains(normalizedQuery) ||
                habit.content.lowercase().contains(normalizedQuery) ||
                habit.tags.any { it.lowercase().contains(normalizedQuery) }
            matchesCategory && matchesQuery
        }
    }

    fun totalCheckIns(habits: List<HabitItem>): Int = habits.sumOf { it.completedDates.distinct().size }

    fun completionRate(habits: List<HabitItem>): Int {
        if (habits.isEmpty()) return 0
        val completedToday = habits.count { StreakUtils.today() in it.completedDates }
        return (completedToday * 100f / habits.size).toInt()
    }

    fun weeklyCheckIns(habits: List<HabitItem>): Int {
        val weekStart = LocalDate.now().minusDays(6)
        return habits.sumOf { habit ->
            habit.completedDates.count {
                runCatching { LocalDate.parse(it) }.getOrNull()?.let { date -> date >= weekStart } == true
            }
        }
    }

    fun monthlyCheckIns(habits: List<HabitItem>): Int {
        val monthPrefix = LocalDate.now().format(monthFormatter)
        return habits.sumOf { habit ->
            habit.completedDates.count { it.startsWith(monthPrefix) }
        }
    }

    fun monthGrid(today: String, completedDates: Set<String>): List<CalendarDay> {
        val currentDate = runCatching { LocalDate.parse(today) }.getOrDefault(LocalDate.now())
        val firstDay = currentDate.withDayOfMonth(1)
        val startPadding = firstDay.dayOfWeek.value % 7
        val monthLength = currentDate.lengthOfMonth()

        val cells = mutableListOf<CalendarDay>()
        repeat(startPadding) { cells += CalendarDay.Empty }
        for (day in 1..monthLength) {
            val date = currentDate.withDayOfMonth(day)
            cells += CalendarDay.Day(
                day = day,
                isToday = date == currentDate,
                isCompleted = date.toString() in completedDates
            )
        }
        val endPadding = (7 - cells.size % 7) % 7
        repeat(endPadding) { cells += CalendarDay.Empty }
        return cells
    }
}

sealed interface CalendarDay {
    data object Empty : CalendarDay

    data class Day(
        val day: Int,
        val isToday: Boolean,
        val isCompleted: Boolean
    ) : CalendarDay
}
