package com.streak.app.util;

import com.streak.app.model.Badge;
import com.streak.app.model.HabitItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 根据习惯打卡数据评估勋章是否点亮。全部基于真实数据，无需额外存储。
 */
public final class BadgeUtils {

    private BadgeUtils() {
    }

    public static List<Badge> evaluate(List<HabitItem> habits) {
        if (habits == null) {
            habits = new ArrayList<>();
        }

        int habitCount = habits.size();
        int totalCheckIns = HabitUtils.totalCheckIns(habits);

        int bestStreak = 0;
        boolean anyWithPhoto = false;
        Set<String> categories = new HashSet<>();
        int todayCompleted = 0;
        String today = HabitUtils.today();
        for (HabitItem item : habits) {
            // 勋章看历史最长连续，断卡后已达成的成就不再熄灭
            bestStreak = Math.max(bestStreak, HabitUtils.longestStreak(item.getCompletedDates()));
            if (item.getImageUri() != null && !item.getImageUri().trim().isEmpty()) {
                anyWithPhoto = true;
            }
            categories.add(item.getCategory());
            if (item.getCompletedDates().contains(today)) {
                todayCompleted++;
            }
        }
        boolean allDoneToday = habitCount > 0 && todayCompleted == habitCount;

        List<Badge> badges = new ArrayList<>();
        badges.add(new Badge("first_habit", "启程", "创建第一个习惯", "🌱", habitCount >= 1));
        badges.add(new Badge("first_checkin", "第一步", "完成第一次打卡", "👣", totalCheckIns >= 1));
        badges.add(new Badge("streak_3", "三日不辍", "连续打卡 3 天", "🔥", bestStreak >= 3));
        badges.add(new Badge("streak_7", "一周坚持", "连续打卡 7 天", "📅", bestStreak >= 7));
        badges.add(new Badge("streak_30", "月度恒心", "连续打卡 30 天", "🏆", bestStreak >= 30));
        badges.add(new Badge("checkin_50", "积少成多", "累计打卡 50 次", "💪", totalCheckIns >= 50));
        badges.add(new Badge("checkin_100", "百炼成钢", "累计打卡 100 次", "💎", totalCheckIns >= 100));
        badges.add(new Badge("habits_5", "多面手", "同时拥有 5 个习惯", "🎯", habitCount >= 5));
        badges.add(new Badge("all_categories", "全能", "习惯覆盖 5 个分类", "🌈", categories.size() >= 5));
        badges.add(new Badge("all_today", "今日全勤", "今天完成全部习惯", "⭐", allDoneToday));
        badges.add(new Badge("photo", "影像记录", "为习惯添加一张照片", "📸", anyWithPhoto));
        return badges;
    }

    public static int unlockedCount(List<Badge> badges) {
        int count = 0;
        for (Badge badge : badges) {
            if (badge.isUnlocked()) {
                count++;
            }
        }
        return count;
    }
}
