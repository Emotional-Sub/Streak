package com.streak.app.reminder;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.streak.app.model.HabitItem;

import java.time.LocalDateTime;
import java.time.ZoneId;

public class ReminderScheduler {
    private final Context context;
    private final AlarmManager alarmManager;

    public ReminderScheduler(Context context) {
        this.context = context.getApplicationContext();
        this.alarmManager = (AlarmManager) this.context.getSystemService(Context.ALARM_SERVICE);
    }

    public void schedule(HabitItem habit) {
        if (!habit.isReminderEnabled()) {
            cancel(habit.getId());
            return;
        }

        int hour = 20;
        int minute = 0;
        String reminderTime = habit.getReminderTime();
        String[] parts = reminderTime == null ? new String[0] : reminderTime.split(":");
        try {
            if (parts.length > 0) {
                hour = Integer.parseInt(parts[0].trim());
            }
            if (parts.length > 1) {
                minute = Integer.parseInt(parts[1].trim());
            }
        } catch (NumberFormatException ignored) {
            hour = 20;
            minute = 0;
        }
        // 钳制到合法范围，避免导入的脏数据（如 "25:70"）让 withHour/withMinute 抛
        // DateTimeException，进而中断整个调度（开机重排时尤其会连累后续习惯）。
        if (hour < 0 || hour > 23) {
            hour = 20;
        }
        if (minute < 0 || minute > 59) {
            minute = 0;
        }

        LocalDateTime trigger = LocalDateTime.now()
                .withHour(hour)
                .withMinute(minute)
                .withSecond(0)
                .withNano(0);
        if (trigger.isBefore(LocalDateTime.now())) {
            trigger = trigger.plusDays(1);
        }

        long triggerAtMillis = trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        PendingIntent pendingIntent = pendingIntent(habit);
        if (canScheduleExact()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
                return;
            } catch (SecurityException ignored) {
                // 权限被撤销时退回非精确闹钟。
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        }
    }

    /**
     * Android 12（API 31）起精确闹钟需要单独的权限/用户授权，调度前先确认。
     */
    private boolean canScheduleExact() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return alarmManager != null && alarmManager.canScheduleExactAlarms();
    }

    public void cancel(long habitId) {
        alarmManager.cancel(pendingIntent(habitId));
    }

    /**
     * 习惯 ID 是毫秒时间戳（超出 int 范围），直接 (int) 截断会让不同习惯撞码、
     * 共用同一个闹钟/通知。用 Long.hashCode 把高低位混合，降低碰撞概率。
     */
    private static int requestCode(long habitId) {
        return Long.hashCode(habitId) & 0x7FFFFFFF;
    }

    private PendingIntent pendingIntent(HabitItem habit) {
        Intent intent = new Intent(context, ReminderReceiver.class)
                .putExtra(ReminderReceiver.EXTRA_TITLE, habit.getTitle())
                .putExtra(ReminderReceiver.EXTRA_CONTENT, habit.getContent())
                .putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, requestCode(habit.getId()))
                .putExtra(ReminderReceiver.EXTRA_HABIT_ID, habit.getId());
        return PendingIntent.getBroadcast(
                context,
                requestCode(habit.getId()),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private PendingIntent pendingIntent(long habitId) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        return PendingIntent.getBroadcast(
                context,
                requestCode(habitId),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
