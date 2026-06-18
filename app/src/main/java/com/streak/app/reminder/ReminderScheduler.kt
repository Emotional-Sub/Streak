package com.streak.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.streak.app.data.HabitItem
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(habit: HabitItem) {
        if (!habit.reminderEnabled) {
            cancel(habit.id)
            return
        }

        val parts = habit.reminderTime.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 20
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0

        var trigger = LocalDateTime.now()
            .withHour(hour)
            .withMinute(minute)
            .withSecond(0)
            .withNano(0)

        if (trigger.isBefore(LocalDateTime.now())) {
            trigger = trigger.plusDays(1)
        }

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            pendingIntent(habit)
        )
    }

    fun cancel(habitId: Long) {
        alarmManager.cancel(pendingIntent(habitId))
    }

    private fun pendingIntent(habit: HabitItem): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_TITLE, habit.title)
            putExtra(ReminderReceiver.EXTRA_CONTENT, habit.content)
            putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, habit.id.toInt())
        }
        return PendingIntent.getBroadcast(
            context,
            habit.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun pendingIntent(habitId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            habitId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
