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
        String[] parts = habit.getReminderTime().split(":");
        try {
            if (parts.length > 0) {
                hour = Integer.parseInt(parts[0]);
            }
            if (parts.length > 1) {
                minute = Integer.parseInt(parts[1]);
            }
        } catch (NumberFormatException ignored) {
            hour = 20;
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
            }
        } catch (SecurityException e) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
            }
        }
    }

    public void cancel(long habitId) {
        alarmManager.cancel(pendingIntent(habitId));
    }

    private PendingIntent pendingIntent(HabitItem habit) {
        Intent intent = new Intent(context, ReminderReceiver.class)
                .putExtra(ReminderReceiver.EXTRA_TITLE, habit.getTitle())
                .putExtra(ReminderReceiver.EXTRA_CONTENT, habit.getContent())
                .putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, (int) habit.getId());
        return PendingIntent.getBroadcast(
                context,
                (int) habit.getId(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private PendingIntent pendingIntent(long habitId) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        return PendingIntent.getBroadcast(
                context,
                (int) habitId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
