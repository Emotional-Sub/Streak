package com.streak.app.util;

import com.streak.app.model.HabitItem;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 打卡数据的轻量「智能分析」：全部基于既有 completedDates 聚合，不依赖任何新存储、
 * 不引入第三方库/机器学习。用于统计页的「智能洞察」卡片与习惯详情页。
 *
 * <p>提供三类洞察：</p>
 * <ul>
 *   <li>坚持排行：按历史最长连续天数挑出「最能坚持」与「最易中断」的习惯；</li>
 *   <li>星期偏好：把全部打卡按星期几聚合，找出打卡最活跃的一天（周一~周日）；</li>
 *   <li>断卡预警：距今连续未打卡天数达到阈值的习惯，提醒尽快续上。</li>
 * </ul>
 *
 * <p>所有方法对 null / 空数据做兜底，绝不抛异常，便于 UI 直接调用。</p>
 */
public final class HabitAnalytics {

    /** 断卡预警阈值：距最近一次打卡已达到/超过这么多天，视为需要提醒。 */
    public static final int STALE_WARNING_DAYS = 3;

    private HabitAnalytics() {
    }

    /**
     * 距今的「连续未打卡天数」：今天算第 0 天。
     * 今天已打卡 -> 0；最近一次是昨天 -> 1；从未打卡 -> Integer.MAX_VALUE（视为极久未打）。
     * 用于断卡预警排序与展示。
     */
    public static int daysSinceLastCheckIn(HabitItem item) {
        if (item == null || item.getCompletedDates() == null) {
            return Integer.MAX_VALUE;
        }
        LocalDate today = LocalDate.now();
        LocalDate latest = null;
        for (String date : item.getCompletedDates()) {
            try {
                LocalDate d = LocalDate.parse(date);
                // 忽略未来日期（导入/编辑脏数据），只看不晚于今天的最近一次
                if (d.isAfter(today)) {
                    continue;
                }
                if (latest == null || d.isAfter(latest)) {
                    latest = d;
                }
            } catch (Exception ignored) {
            }
        }
        if (latest == null) {
            return Integer.MAX_VALUE;
        }
        return (int) java.time.temporal.ChronoUnit.DAYS.between(latest, today);
    }

    /**
     * 「最能坚持」的习惯：历史最长连续天数最大者。并列时取当前连续更长者，
     * 再并列取更早创建（列表靠前）者，保证结果稳定。无习惯返回 null。
     */
    public static HabitItem mostConsistent(List<HabitItem> habits) {
        if (habits == null || habits.isEmpty()) {
            return null;
        }
        HabitItem best = null;
        int bestLongest = -1;
        int bestCurrent = -1;
        for (HabitItem item : habits) {
            int longest = HabitUtils.longestStreak(item.getCompletedDates());
            int current = HabitUtils.currentStreak(item.getCompletedDates());
            if (longest > bestLongest
                    || (longest == bestLongest && current > bestCurrent)) {
                best = item;
                bestLongest = longest;
                bestCurrent = current;
            }
        }
        return best;
    }

    /**
     * 「最需要关注」的习惯：距今未打卡天数最多者（越久没打越靠前）。
     * 全部都是今天打过卡（无人掉队）时返回 null——没什么可提醒的。
     */
    public static HabitItem mostAtRisk(List<HabitItem> habits) {
        if (habits == null || habits.isEmpty()) {
            return null;
        }
        HabitItem worst = null;
        int worstGap = 0;
        for (HabitItem item : habits) {
            int gap = daysSinceLastCheckIn(item);
            if (gap > worstGap) {
                worst = item;
                worstGap = gap;
            }
        }
        return worst;
    }

    /**
     * 需要断卡预警的习惯：距今连续未打卡天数 >= {@link #STALE_WARNING_DAYS}。
     * 按未打卡天数从多到少排序，越危急越靠前。从未打卡的习惯也纳入（gap 视为极大）。
     */
    public static List<HabitItem> staleHabits(List<HabitItem> habits) {
        List<HabitItem> result = new ArrayList<>();
        if (habits == null) {
            return result;
        }
        for (HabitItem item : habits) {
            if (daysSinceLastCheckIn(item) >= STALE_WARNING_DAYS) {
                result.add(item);
            }
        }
        result.sort((a, b) -> Integer.compare(
                daysSinceLastCheckIn(b), daysSinceLastCheckIn(a)));
        return result;
    }

    /**
     * 全部习惯的打卡按「星期几」聚合的次数。返回长度 7 的数组，
     * 下标 0=周一 ... 6=周日（对齐 {@link DayOfWeek#getValue()} - 1）。
     * 同一习惯同一天多条记录只算一次（按 (习惯, 日期) 去重）。
     */
    public static int[] checkInsByWeekday(List<HabitItem> habits) {
        int[] buckets = new int[7];
        if (habits == null) {
            return buckets;
        }
        for (HabitItem item : habits) {
            if (item == null || item.getCompletedDates() == null) {
                continue;
            }
            Set<String> seen = new HashSet<>();
            for (String date : item.getCompletedDates()) {
                if (date == null || !seen.add(date)) {
                    continue;
                }
                try {
                    LocalDate d = LocalDate.parse(date);
                    buckets[d.getDayOfWeek().getValue() - 1]++;
                } catch (Exception ignored) {
                }
            }
        }
        return buckets;
    }

    /**
     * 打卡最活跃的星期几（1=周一 ... 7=周日，对齐 {@link DayOfWeek#getValue()}）。
     * 完全没有打卡数据时返回 -1；并列时取更靠前的一天（周一优先）。
     */
    public static int mostActiveWeekday(List<HabitItem> habits) {
        int[] buckets = checkInsByWeekday(habits);
        int bestIndex = -1;
        int bestCount = 0;
        for (int i = 0; i < buckets.length; i++) {
            if (buckets[i] > bestCount) {
                bestCount = buckets[i];
                bestIndex = i;
            }
        }
        return bestIndex < 0 ? -1 : bestIndex + 1;
    }
}
