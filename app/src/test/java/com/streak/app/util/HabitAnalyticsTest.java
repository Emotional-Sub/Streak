package com.streak.app.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.streak.app.model.HabitItem;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * HabitAnalytics 单测：纯 JVM 逻辑（不依赖 Android framework），直接跑本地 JUnit。
 * 覆盖断卡天数、坚持/风险排行、星期聚合、断卡预警筛选与排序，及 null/空兜底。
 */
public class HabitAnalyticsTest {

    private static String daysAgo(int days) {
        return LocalDate.now().minusDays(days).toString();
    }

    private static HabitItem habit(String title, List<String> completedDates) {
        return new HabitItem(1L, title, "内容", "20:00", "2026-01-01 10:00",
                null, "学习", new ArrayList<>(), new ArrayList<>(completedDates), true);
    }

    // ---- daysSinceLastCheckIn ----

    @Test
    public void daysSinceLastCheckIn_checkedInToday_isZero() {
        assertEquals(0, HabitAnalytics.daysSinceLastCheckIn(
                habit("A", Arrays.asList(daysAgo(0), daysAgo(2)))));
    }

    @Test
    public void daysSinceLastCheckIn_latestYesterday_isOne() {
        assertEquals(1, HabitAnalytics.daysSinceLastCheckIn(
                habit("A", Arrays.asList(daysAgo(1), daysAgo(5)))));
    }

    @Test
    public void daysSinceLastCheckIn_neverCheckedIn_isMaxValue() {
        assertEquals(Integer.MAX_VALUE, HabitAnalytics.daysSinceLastCheckIn(
                habit("A", Collections.emptyList())));
    }

    @Test
    public void daysSinceLastCheckIn_nullItem_isMaxValue() {
        assertEquals(Integer.MAX_VALUE, HabitAnalytics.daysSinceLastCheckIn(null));
    }

    @Test
    public void daysSinceLastCheckIn_ignoresFutureDates() {
        // 存在未来日期（脏数据）时，只看不晚于今天的最近一次
        HabitItem item = habit("A", Arrays.asList("2999-01-01", daysAgo(2)));
        assertEquals(2, HabitAnalytics.daysSinceLastCheckIn(item));
    }

    // ---- mostConsistent ----

    @Test
    public void mostConsistent_picksLongestHistoricalStreak() {
        HabitItem weak = habit("弱", Arrays.asList("2026-01-01", "2026-01-02"));
        HabitItem strong = habit("强",
                Arrays.asList("2026-03-01", "2026-03-02", "2026-03-03", "2026-03-04"));
        assertSame(strong, HabitAnalytics.mostConsistent(Arrays.asList(weak, strong)));
    }

    @Test
    public void mostConsistent_emptyOrNull_isNull() {
        assertNull(HabitAnalytics.mostConsistent(null));
        assertNull(HabitAnalytics.mostConsistent(new ArrayList<>()));
    }

    // ---- mostAtRisk ----

    @Test
    public void mostAtRisk_picksLongestGap() {
        HabitItem fresh = habit("今天打过", Arrays.asList(daysAgo(0)));
        HabitItem stale = habit("很久没打", Arrays.asList(daysAgo(10)));
        assertSame(stale, HabitAnalytics.mostAtRisk(Arrays.asList(fresh, stale)));
    }

    @Test
    public void mostAtRisk_allCheckedInToday_isNull() {
        // 所有习惯今天都打了卡（gap=0），没什么可提醒 -> null
        HabitItem a = habit("A", Arrays.asList(daysAgo(0)));
        HabitItem b = habit("B", Arrays.asList(daysAgo(0)));
        assertNull(HabitAnalytics.mostAtRisk(Arrays.asList(a, b)));
    }

    // ---- staleHabits ----

    @Test
    public void staleHabits_filtersAndSortsByGapDescending() {
        HabitItem fresh = habit("今天", Arrays.asList(daysAgo(0)));      // gap 0，不入选
        HabitItem gap3 = habit("三天", Arrays.asList(daysAgo(3)));       // gap 3，入选
        HabitItem gap7 = habit("七天", Arrays.asList(daysAgo(7)));       // gap 7，入选
        List<HabitItem> stale = HabitAnalytics.staleHabits(Arrays.asList(fresh, gap3, gap7));
        assertEquals(2, stale.size());
        // 越久没打越靠前
        assertSame(gap7, stale.get(0));
        assertSame(gap3, stale.get(1));
    }

    @Test
    public void staleHabits_belowThreshold_excluded() {
        // gap 2 < 阈值 3，不入选
        HabitItem gap2 = habit("两天", Arrays.asList(daysAgo(2)));
        assertTrue(HabitAnalytics.staleHabits(Arrays.asList(gap2)).isEmpty());
    }

    @Test
    public void staleHabits_null_isEmpty() {
        assertTrue(HabitAnalytics.staleHabits(null).isEmpty());
    }

    // ---- checkInsByWeekday ----

    @Test
    public void checkInsByWeekday_bucketsByDayOfWeek() {
        // 2026-01-05 是周一 -> 下标 0；2026-01-11 是周日 -> 下标 6
        HabitItem item = habit("A", Arrays.asList("2026-01-05", "2026-01-11"));
        int[] buckets = HabitAnalytics.checkInsByWeekday(Arrays.asList(item));
        assertEquals(1, buckets[0]);
        assertEquals(1, buckets[6]);
    }

    @Test
    public void checkInsByWeekday_deduplicatesSameDatePerHabit() {
        HabitItem item = habit("A", Arrays.asList("2026-01-05", "2026-01-05"));
        int[] buckets = HabitAnalytics.checkInsByWeekday(Arrays.asList(item));
        assertEquals(1, buckets[0]);
    }

    @Test
    public void checkInsByWeekday_null_isAllZero() {
        assertArrayEquals(new int[7], HabitAnalytics.checkInsByWeekday(null));
    }

    // ---- mostActiveWeekday ----

    @Test
    public void mostActiveWeekday_returnsIsoDayValue() {
        // 周一(2026-01-05) 两次，周日(2026-01-11) 一次 -> 最活跃是周一，ISO 值 1
        HabitItem item = habit("A",
                Arrays.asList("2026-01-05", "2026-01-12", "2026-01-11"));
        assertEquals(1, HabitAnalytics.mostActiveWeekday(Arrays.asList(item)));
    }

    @Test
    public void mostActiveWeekday_noData_isMinusOne() {
        assertEquals(-1, HabitAnalytics.mostActiveWeekday(new ArrayList<>()));
        assertEquals(-1, HabitAnalytics.mostActiveWeekday(
                Arrays.asList(habit("A", Collections.emptyList()))));
    }
}
