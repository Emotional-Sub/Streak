package com.streak.app

import com.streak.app.util.StreakUtils
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StreakUtilsTest {
    @Test
    fun returnsCountForContinuousDates() {
        val today = LocalDate.now()
        val dates = listOf(
            today.toString(),
            today.minusDays(1).toString(),
            today.minusDays(2).toString()
        )
        assertEquals(3, StreakUtils.computeCurrentStreak(dates))
    }

    @Test
    fun allowsStreakStartingYesterday() {
        val today = LocalDate.now()
        val dates = listOf(
            today.minusDays(1).toString(),
            today.minusDays(2).toString()
        )
        assertEquals(2, StreakUtils.computeCurrentStreak(dates))
    }
}
