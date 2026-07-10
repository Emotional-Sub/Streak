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

    public static int completionRate(List<HabitItem> habits) {
        if (habits == null || habits.isEmpty()) {
            return 0;
        }
        String today = today();
        int completed = 0;
        for (HabitItem item : habits) {
            List<String> dates = item.getCompletedDates();
            if (dates != null && dates.contains(today)) {
                completed++;
            }
        }
        return (int) ((completed * 100f) / habits.size());
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

    public static List<CalendarCell> buildMonthCells(String today, Set<String> completedDates) {
        Set<String> done = completedDates == null ? java.util.Collections.emptySet() : completedDates;
        LocalDate currentDate;
        try {
            currentDate = LocalDate.parse(today);
        } catch (Exception ignored) {
            currentDate = LocalDate.now();
        }

        YearMonth yearMonth = YearMonth.from(currentDate);
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
            cells.add(new CalendarCell(false, day, dateText, date.equals(currentDate), done.contains(dateText)));
        }
        int endPadding = (7 - cells.size() % 7) % 7;
        for (int i = 0; i < endPadding; i++) {
            cells.add(new CalendarCell(true, 0, "", false, false));
        }
        return cells;
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
