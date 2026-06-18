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

    public static List<HabitItem> filterHabits(List<HabitItem> source, String query, String category) {
        List<HabitItem> result = new ArrayList<>();
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
        int total = 0;
        for (HabitItem item : habits) {
            total += new HashSet<>(item.getCompletedDates()).size();
        }
        return total;
    }

    public static int completionRate(List<HabitItem> habits) {
        if (habits.isEmpty()) {
            return 0;
        }
        String today = today();
        int completed = 0;
        for (HabitItem item : habits) {
            if (item.getCompletedDates().contains(today)) {
                completed++;
            }
        }
        return (int) ((completed * 100f) / habits.size());
    }

    public static int weeklyCheckIns(List<HabitItem> habits) {
        LocalDate weekStart = LocalDate.now().minusDays(6);
        int total = 0;
        for (HabitItem item : habits) {
            for (String date : item.getCompletedDates()) {
                try {
                    if (!LocalDate.parse(date).isBefore(weekStart)) {
                        total++;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return total;
    }

    public static int monthlyCheckIns(List<HabitItem> habits) {
        String monthPrefix = LocalDate.now().format(MONTH_FORMAT);
        int total = 0;
        for (HabitItem item : habits) {
            for (String date : item.getCompletedDates()) {
                if (date != null && date.startsWith(monthPrefix)) {
                    total++;
                }
            }
        }
        return total;
    }

    public static List<CalendarCell> buildMonthCells(String today, Set<String> completedDates) {
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
            cells.add(new CalendarCell(false, day, dateText, date.equals(currentDate), completedDates.contains(dateText)));
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
