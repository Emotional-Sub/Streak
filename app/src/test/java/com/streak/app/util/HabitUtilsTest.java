package com.streak.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.streak.app.model.CalendarCell;
import com.streak.app.model.HabitItem;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class HabitUtilsTest {

    private static String daysAgo(int days) {
        return LocalDate.now().minusDays(days).toString();
    }

    private static HabitItem habit(String title, String category, List<String> tags,
                                   List<String> completedDates, boolean reminderEnabled) {
        return new HabitItem(1L, title, "内容", "20:00", "2026-01-01 10:00",
                null, category, tags, completedDates, reminderEnabled);
    }

    // ---- currentStreak ----

    @Test
    public void currentStreak_emptyOrNull_isZero() {
        assertEquals(0, HabitUtils.currentStreak(null));
        assertEquals(0, HabitUtils.currentStreak(new ArrayList<>()));
    }

    @Test
    public void currentStreak_consecutiveEndingToday() {
        List<String> dates = Arrays.asList(daysAgo(0), daysAgo(1), daysAgo(2));
        assertEquals(3, HabitUtils.currentStreak(dates));
    }

    @Test
    public void currentStreak_endingYesterday_stillCounts() {
        List<String> dates = Arrays.asList(daysAgo(1), daysAgo(2));
        assertEquals(2, HabitUtils.currentStreak(dates));
    }

    @Test
    public void currentStreak_brokenChain_resetsAtGap() {
        // 今天、昨天连续，但前天缺失 -> 只数到昨天为止的连续段
        List<String> dates = Arrays.asList(daysAgo(0), daysAgo(1), daysAgo(3));
        assertEquals(2, HabitUtils.currentStreak(dates));
    }

    @Test
    public void currentStreak_staleDates_isZero() {
        // 最近一次是 5 天前，今天和昨天都没有 -> 0
        List<String> dates = Arrays.asList(daysAgo(5), daysAgo(6));
        assertEquals(0, HabitUtils.currentStreak(dates));
    }

    @Test
    public void currentStreak_ignoresUnparseableDates() {
        List<String> dates = Arrays.asList(daysAgo(0), "not-a-date", daysAgo(1));
        assertEquals(2, HabitUtils.currentStreak(dates));
    }

    // ---- filterHabits ----

    @Test
    public void filterHabits_byCategory() {
        List<HabitItem> source = Arrays.asList(
                habit("晨跑", "运动", Collections.emptyList(), new ArrayList<>(), true),
                habit("背单词", "学习", Collections.emptyList(), new ArrayList<>(), true)
        );
        List<HabitItem> result = HabitUtils.filterHabits(source, "", "运动");
        assertEquals(1, result.size());
        assertEquals("晨跑", result.get(0).getTitle());
    }

    @Test
    public void filterHabits_allCategoryReturnsEverything() {
        List<HabitItem> source = Arrays.asList(
                habit("晨跑", "运动", Collections.emptyList(), new ArrayList<>(), true),
                habit("背单词", "学习", Collections.emptyList(), new ArrayList<>(), true)
        );
        assertEquals(2, HabitUtils.filterHabits(source, "", "全部").size());
    }

    @Test
    public void filterHabits_queryMatchesTitleContentAndTags() {
        List<HabitItem> source = Arrays.asList(
                habit("晨跑", "运动", Arrays.asList("有氧"), new ArrayList<>(), true),
                habit("阅读", "学习", Arrays.asList("英语"), new ArrayList<>(), true)
        );
        assertEquals(1, HabitUtils.filterHabits(source, "有氧", "全部").size());
        assertEquals(1, HabitUtils.filterHabits(source, "英语", "全部").size());
        assertEquals(0, HabitUtils.filterHabits(source, "不存在", "全部").size());
    }

    @Test
    public void filterHabits_queryIsCaseInsensitive() {
        List<HabitItem> source = Arrays.asList(
                habit("Morning Run", "运动", Collections.emptyList(), new ArrayList<>(), true)
        );
        assertEquals(1, HabitUtils.filterHabits(source, "morning", "全部").size());
    }

    // ---- totalCheckIns ----

    @Test
    public void totalCheckIns_deduplicatesDatesPerHabit() {
        List<HabitItem> source = Arrays.asList(
                habit("A", "学习", Collections.emptyList(),
                        new ArrayList<>(Arrays.asList("2026-01-01", "2026-01-01", "2026-01-02")), true),
                habit("B", "学习", Collections.emptyList(),
                        new ArrayList<>(Arrays.asList("2026-01-01")), true)
        );
        assertEquals(3, HabitUtils.totalCheckIns(source));
    }

    // ---- completionRate ----

    @Test
    public void completionRate_emptyIsZero() {
        assertEquals(0, HabitUtils.completionRate(new ArrayList<>()));
    }

    @Test
    public void completionRate_halfCompletedToday() {
        List<HabitItem> source = Arrays.asList(
                habit("A", "学习", Collections.emptyList(),
                        new ArrayList<>(Arrays.asList(daysAgo(0))), true),
                habit("B", "学习", Collections.emptyList(), new ArrayList<>(), true)
        );
        assertEquals(50, HabitUtils.completionRate(source));
    }

    // ---- weeklyCheckIns ----

    @Test
    public void weeklyCheckIns_countsLastSevenDaysOnly() {
        List<HabitItem> source = Arrays.asList(
                habit("A", "学习", Collections.emptyList(),
                        new ArrayList<>(Arrays.asList(daysAgo(0), daysAgo(6), daysAgo(7))), true)
        );
        // daysAgo(7) 超出最近 7 天窗口（含今天共 7 天，最早是 daysAgo(6)）
        assertEquals(2, HabitUtils.weeklyCheckIns(source));
    }

    // ---- buildMonthCells ----

    @Test
    public void buildMonthCells_lengthIsMultipleOfSeven() {
        List<CalendarCell> cells = HabitUtils.buildMonthCells("2026-06-15", Collections.emptySet());
        assertEquals(0, cells.size() % 7);
    }

    @Test
    public void buildMonthCells_marksTodayAndCompleted() {
        List<CalendarCell> cells = HabitUtils.buildMonthCells(
                "2026-06-15",
                new java.util.HashSet<>(Arrays.asList("2026-06-10"))
        );
        boolean foundToday = false;
        boolean foundCompleted = false;
        for (CalendarCell cell : cells) {
            if (cell.isEmpty()) {
                continue;
            }
            if ("2026-06-15".equals(cell.getDate())) {
                foundToday = cell.isToday();
            }
            if ("2026-06-10".equals(cell.getDate())) {
                foundCompleted = cell.isCompleted();
            }
        }
        assertTrue(foundToday);
        assertTrue(foundCompleted);
    }

    @Test
    public void buildMonthCells_june2026HasThirtyDayCells() {
        List<CalendarCell> cells = HabitUtils.buildMonthCells("2026-06-15", Collections.emptySet());
        int dayCells = 0;
        for (CalendarCell cell : cells) {
            if (!cell.isEmpty()) {
                dayCells++;
            }
        }
        assertEquals(30, dayCells);
    }

    @Test
    public void buildMonthCells_invalidDateFallsBackToNow() {
        // 不应抛异常，且返回的单元格数量是 7 的倍数
        List<CalendarCell> cells = HabitUtils.buildMonthCells("garbage", Collections.emptySet());
        assertFalse(cells.isEmpty());
        assertEquals(0, cells.size() % 7);
    }

    private static int countDayCells(List<CalendarCell> cells) {
        int n = 0;
        for (CalendarCell c : cells) {
            if (!c.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void buildMonthCells_leapFebruaryHas29Days() {
        assertEquals(29, countDayCells(
                HabitUtils.buildMonthCells("2024-02-10", Collections.emptySet())));
    }

    @Test
    public void buildMonthCells_nonLeapFebruaryHas28Days() {
        assertEquals(28, countDayCells(
                HabitUtils.buildMonthCells("2025-02-10", Collections.emptySet())));
    }

    @Test
    public void buildMonthCells_31DayMonth() {
        assertEquals(31, countDayCells(
                HabitUtils.buildMonthCells("2026-01-15", Collections.emptySet())));
    }

    @Test
    public void buildMonthCells_yearBoundaryDecember() {
        List<CalendarCell> cells = HabitUtils.buildMonthCells("2026-12-25", Collections.emptySet());
        assertEquals(31, countDayCells(cells));
        assertEquals(0, cells.size() % 7);
    }

    @Test
    public void buildMonthCells_monthStartingOnSundayHasNoLeadingPadding() {
        // 2026-03-01 是星期日；周日在本实现里是每行第一格，故无前导空格
        List<CalendarCell> cells = HabitUtils.buildMonthCells("2026-03-01", Collections.emptySet());
        assertFalse(cells.get(0).isEmpty());
        assertEquals("2026-03-01", cells.get(0).getDate());
    }

    @Test
    public void buildMonthCells_monthStartingOnSaturdayHasSixLeadingPads() {
        // 2026-08-01 是星期六；周日为首列，故其前应有 6 个空格
        List<CalendarCell> cells = HabitUtils.buildMonthCells("2026-08-01", Collections.emptySet());
        for (int i = 0; i < 6; i++) {
            assertTrue(cells.get(i).isEmpty());
        }
        assertFalse(cells.get(6).isEmpty());
        assertEquals("2026-08-01", cells.get(6).getDate());
    }

    // ---- longestStreak ----

    @Test
    public void longestStreak_emptyOrNull_isZero() {
        assertEquals(0, HabitUtils.longestStreak(null));
        assertEquals(0, HabitUtils.longestStreak(new ArrayList<>()));
    }

    @Test
    public void longestStreak_singleSegment() {
        List<String> dates = Arrays.asList("2026-01-01", "2026-01-02", "2026-01-03");
        assertEquals(3, HabitUtils.longestStreak(dates));
    }

    @Test
    public void longestStreak_picksMaxAcrossSegments() {
        // 两段：2 天 和 4 天，取最长的 4
        List<String> dates = Arrays.asList(
                "2026-01-01", "2026-01-02",
                "2026-02-10", "2026-02-11", "2026-02-12", "2026-02-13");
        assertEquals(4, HabitUtils.longestStreak(dates));
    }

    @Test
    public void longestStreak_isIndependentOfToday() {
        // 全是很久以前的日期，currentStreak 会是 0，但 longestStreak 仍应数出连续段
        List<String> dates = Arrays.asList("2000-01-01", "2000-01-02", "2000-01-03");
        assertEquals(0, HabitUtils.currentStreak(dates));
        assertEquals(3, HabitUtils.longestStreak(dates));
    }

    @Test
    public void longestStreak_deduplicatesAndIgnoresUnparseable() {
        List<String> dates = Arrays.asList(
                "2026-01-01", "2026-01-01", "not-a-date", "2026-01-02");
        assertEquals(2, HabitUtils.longestStreak(dates));
    }

    // ---- filterHabits / totalCheckIns / completionRate null 防护 ----

    @Test
    public void filterHabits_nullSourceReturnsEmpty() {
        assertTrue(HabitUtils.filterHabits(null, "x", "全部").isEmpty());
    }

    @Test
    public void totalCheckIns_nullIsZero() {
        assertEquals(0, HabitUtils.totalCheckIns(null));
    }

    @Test
    public void completionRate_nullIsZero() {
        assertEquals(0, HabitUtils.completionRate(null));
    }

    @Test
    public void uniqueCheckIns_nullItemIsZero() {
        assertEquals(0, HabitUtils.uniqueCheckIns(null));
    }
}
