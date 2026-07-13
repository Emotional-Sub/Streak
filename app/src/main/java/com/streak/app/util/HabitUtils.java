package com.streak.app.util;

import com.streak.app.model.CalendarCell;
import com.streak.app.model.HabitItem;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HabitUtils {
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM", Locale.CHINA);

    private HabitUtils() {
    }

    public static String today() {
        return LocalDate.now().toString();
    }

    public static List<String> categories() {
        return Arrays.asList("全部", "学习", "运动", "生活", "工作", "阅读");
    }

    public static int currentStreak(List<String> completedDates) {
        if (completedDates == null || completedDates.isEmpty()) {
            return 0;
        }
        Set<LocalDate> dateSet = new HashSet<>();
        for (String date : completedDates) {
            try {
                dateSet.add(LocalDate.parse(date));
            } catch (Exception ignored) {
            }
        }
        if (dateSet.isEmpty()) {
            return 0;
        }

        LocalDate cursor = LocalDate.now();
        if (!dateSet.contains(cursor) && !dateSet.contains(cursor.minusDays(1))) {
            return 0;
        }
        if (!dateSet.contains(cursor)) {
            cursor = cursor.minusDays(1);
        }

        int streak = 0;
        while (dateSet.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    /**
     * 历史最长连续打卡天数：扫描全部打卡日期，找出最长的一段连续区间，
     * 与「今天」无关。用于成就勋章——里程碑一旦达成就不应因后来断卡而熄灭。
     * 实时展示的「连续 N 天」仍用 {@link #currentStreak}。
     */
    public static int longestStreak(List<String> completedDates) {
        if (completedDates == null || completedDates.isEmpty()) {
            return 0;
        }
        Set<LocalDate> dateSet = new HashSet<>();
        for (String date : completedDates) {
            try {
                dateSet.add(LocalDate.parse(date));
            } catch (Exception ignored) {
            }
        }
        if (dateSet.isEmpty()) {
            return 0;
        }
        int best = 0;
        for (LocalDate date : dateSet) {
            // 只从「连续段的起点」开始数，避免重复扫描：前一天不在集合里才是起点
            if (dateSet.contains(date.minusDays(1))) {
                continue;
            }
            int length = 0;
            LocalDate cursor = date;
            while (dateSet.contains(cursor)) {
                length++;
                cursor = cursor.plusDays(1);
            }
            best = Math.max(best, length);
        }
        return best;
    }

    public static List<HabitItem> filterHabits(List<HabitItem> source, String query, String category) {
        List<HabitItem> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        String safeQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String safeCategory = category == null ? "全部" : category;
        for (HabitItem item : source) {
            boolean matchesCategory = "全部".equals(safeCategory) || safeCategory.equals(item.getCategory());
            boolean matchesQuery = safeQuery.isEmpty()
                    || safeContains(item.getTitle(), safeQuery)
                    || safeContains(item.getContent(), safeQuery)
                    || tagsContain(item.getTags(), safeQuery);
            if (matchesCategory && matchesQuery) {
                result.add(item);
            }
        }
        return result;
    }

    public static int totalCheckIns(List<HabitItem> habits) {
        if (habits == null) {
            return 0;
        }
        int total = 0;
        for (HabitItem item : habits) {
            total += uniqueCheckIns(item);
        }
        return total;
    }

    /**
     * 单个习惯的有效打卡次数：按日期去重，避免导入备份/补卡引入的重复日期
     * 让计数虚高。全 App 的「打卡次数」口径统一走这里。
     */
    public static int uniqueCheckIns(HabitItem item) {
        if (item == null || item.getCompletedDates() == null) {
            return 0;
        }
        return new HashSet<>(item.getCompletedDates()).size();
    }

    public static int weeklyCheckIns(List<HabitItem> habits) {
        if (habits == null) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.minusDays(6);
        int total = 0;
        for (HabitItem item : habits) {
            if (item.getCompletedDates() == null) {
                continue;
            }
            // 按日期去重，与 uniqueCheckIns 口径一致：同一天的重复记录只算一次
            for (String date : new HashSet<>(item.getCompletedDates())) {
                try {
                    LocalDate d = LocalDate.parse(date);
                    // 落在 [weekStart, today] 窗口内才算；排除未来日期（导入/编辑脏数据）
                    if (!d.isBefore(weekStart) && !d.isAfter(today)) {
                        total++;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return total;
    }

    public static int monthlyCheckIns(List<HabitItem> habits) {
        if (habits == null) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        String monthPrefix = today.format(MONTH_FORMAT);
        int total = 0;
        for (HabitItem item : habits) {
            if (item.getCompletedDates() == null) {
                continue;
            }
            // 按日期去重，与 uniqueCheckIns 口径一致
            for (String date : new HashSet<>(item.getCompletedDates())) {
                if (date == null || !date.startsWith(monthPrefix)) {
                    continue;
                }
                try {
                    // 本月内也要排除未来日期（如本月月底补了将来某天）
                    if (!LocalDate.parse(date).isAfter(today)) {
                        total++;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return total;
    }

    /**
     * 便捷重载：以 today 同时作为「要显示的月份」与「今天」。
     * 单参保持向后兼容（现有测试与不翻月的场景）。
     */
    public static List<CalendarCell> buildMonthCells(String today, Set<String> completedDates) {
        return buildMonthCells(today, today, completedDates);
    }

    /**
     * 构建某个月的日历单元格。
     * @param monthAnchor 决定显示哪个月（取其所在年月），支持翻月时传入目标月的任意一天
     * @param todayDate   真正的今天，仅用于高亮「今日」单元格，与显示月份解耦
     */
    public static List<CalendarCell> buildMonthCells(String monthAnchor, String todayDate,
                                                     Set<String> completedDates) {
        Set<String> done = completedDates == null ? java.util.Collections.emptySet() : completedDates;
        LocalDate anchorDate;
        try {
            anchorDate = LocalDate.parse(monthAnchor);
        } catch (Exception ignored) {
            anchorDate = LocalDate.now();
        }
        String today;
        try {
            today = LocalDate.parse(todayDate).toString();
        } catch (Exception ignored) {
            today = LocalDate.now().toString();
        }

        YearMonth yearMonth = YearMonth.from(anchorDate);
        LocalDate firstDay = yearMonth.atDay(1);
        int startPadding = firstDay.getDayOfWeek().getValue() % 7;
        int monthLength = yearMonth.lengthOfMonth();

        List<CalendarCell> cells = new ArrayList<>();
        for (int i = 0; i < startPadding; i++) {
            cells.add(new CalendarCell(true, 0, "", false, false));
        }
        for (int day = 1; day <= monthLength; day++) {
            LocalDate date = yearMonth.atDay(day);
            String dateText = date.toString();
            cells.add(new CalendarCell(false, day, dateText, dateText.equals(today), done.contains(dateText)));
        }
        int endPadding = (7 - cells.size() % 7) % 7;
        for (int i = 0; i < endPadding; i++) {
            cells.add(new CalendarCell(true, 0, "", false, false));
        }
        return cells;
    }

    /**
     * 判断习惯「当前是否算达标」（全 App 统一口径，列表分组/状态点/统计/组件/提醒都走这里）：
     * - 每天型（weeklyTarget=0）：今天打过卡即达标。
     * - 每周 N 次型：滚动最近 7 天（含今天）窗口内去重打卡数 ≥ N 即达标，与具体哪天无关。
     *   注意是「滚动 7 天」而非「自然周」——不在周一清零，更贴合「最近有没有坚持」的直觉。
     */
    public static boolean isOnTrackToday(HabitItem item) {
        if (item == null) {
            return false;
        }
        List<String> dates = item.getCompletedDates();
        if (dates == null) {
            return false;
        }
        if (!item.isWeeklyGoal()) {
            return dates.contains(today());
        }
        return weeklyDoneCount(item) >= item.getWeeklyTarget();
    }

    /**
     * 习惯在列表/统计里是否算「已完成当前周期」——全 App 完成态的统一判据：
     * - 每天型：今天是否打过卡；
     * - 每周 N 次型：滚动 7 天窗口内是否已达标（等价 isOnTrackToday）。
     * 用它替代散落各处的裸 completedDates.contains(today)，避免每周型已达标却仍显示未打卡。
     */
    public static boolean isCompletedForPeriod(HabitItem item) {
        return isOnTrackToday(item);
    }

    /** 单个习惯最近 7 天（含今天）的去重打卡次数，排除未来日期。 */
    public static int weeklyDoneCount(HabitItem item) {
        if (item == null || item.getCompletedDates() == null) {
            return 0;
        }
        LocalDate todayDate = LocalDate.now();
        LocalDate weekStart = todayDate.minusDays(6);
        int count = 0;
        for (String date : new HashSet<>(item.getCompletedDates())) {
            try {
                LocalDate d = LocalDate.parse(date);
                if (!d.isBefore(weekStart) && !d.isAfter(todayDate)) {
                    count++;
                }
            } catch (Exception ignored) {
            }
        }
        return count;
    }

    /**
     * 完成率：每天型看今天是否打卡；每周 N 次型看本周达标进度（done/target，封顶 100%）。
     * 空列表返回 0。
     */
    public static int completionRate(List<HabitItem> habits) {
        if (habits == null || habits.isEmpty()) {
            return 0;
        }
        float sum = 0f;
        for (HabitItem item : habits) {
            if (item.isWeeklyGoal()) {
                int done = weeklyDoneCount(item);
                sum += Math.min(1f, done / (float) item.getWeeklyTarget());
            } else {
                List<String> dates = item.getCompletedDates();
                if (dates != null && dates.contains(today())) {
                    sum += 1f;
                }
            }
        }
        return (int) (sum * 100f / habits.size());
    }

    /**
     * 全部习惯「上一个 7 天窗口」的去重打卡总数，用于周环比对比（本周用 weeklyCheckIns）。
     */
    public static int lastWeekCheckIns(List<HabitItem> habits) {
        if (habits == null) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        LocalDate thisWeekStart = today.minusDays(6);
        LocalDate lastWeekStart = today.minusDays(13);
        int total = 0;
        for (HabitItem item : habits) {
            if (item.getCompletedDates() == null) {
                continue;
            }
            for (String date : new HashSet<>(item.getCompletedDates())) {
                try {
                    LocalDate d = LocalDate.parse(date);
                    if (!d.isBefore(lastWeekStart) && d.isBefore(thisWeekStart)) {
                        total++;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return total;
    }

    /**
     * 近 N 天（含今天）的打卡总次数：跨全部习惯、按天去重、排除未来日期。
     * 战报「时段成就」维度用它统计近 7 天 / 近 30 天。
     */
    public static int checkInsInLastDays(List<HabitItem> habits, int days) {
        if (habits == null || days <= 0) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(days - 1);
        int total = 0;
        for (HabitItem item : habits) {
            if (item.getCompletedDates() == null) {
                continue;
            }
            for (String date : new HashSet<>(item.getCompletedDates())) {
                try {
                    LocalDate d = LocalDate.parse(date);
                    if (!d.isBefore(start) && !d.isAfter(today)) {
                        total++;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return total;
    }

    /**
     * 近 N 天（含今天）里「至少完成一项打卡」的活跃天数，用于战报展示坚持覆盖度。
     */
    public static int activeDaysInLastDays(List<HabitItem> habits, int days) {
        if (habits == null || days <= 0) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(days - 1);
        Set<LocalDate> activeDays = new HashSet<>();
        for (HabitItem item : habits) {
            if (item.getCompletedDates() == null) {
                continue;
            }
            for (String date : item.getCompletedDates()) {
                try {
                    LocalDate d = LocalDate.parse(date);
                    if (!d.isBefore(start) && !d.isAfter(today)) {
                        activeDays.add(d);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return activeDays.size();
    }

    /** 全部习惯里的历史最长连续天数（跨习惯取最大）。 */
    public static int bestLongestStreak(List<HabitItem> habits) {
        if (habits == null) {
            return 0;
        }
        int best = 0;
        for (HabitItem item : habits) {
            best = Math.max(best, longestStreak(item.getCompletedDates()));
        }
        return best;
    }

    /** 全部习惯里当前连续天数的最大值。 */
    public static int bestCurrentStreak(List<HabitItem> habits) {
        if (habits == null) {
            return 0;
        }
        int best = 0;
        for (HabitItem item : habits) {
            best = Math.max(best, currentStreak(item.getCompletedDates()));
        }
        return best;
    }

    private static boolean safeContains(String text, String query) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(query);
    }

    private static boolean tagsContain(List<String> tags, String query) {
        if (tags == null) {
            return false;
        }
        for (String tag : tags) {
            if (tag != null && tag.toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
        }
        return false;
    }
}
