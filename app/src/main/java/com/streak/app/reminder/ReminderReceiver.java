package com.streak.app.reminder;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.streak.app.R;
import com.streak.app.model.HabitItem;
import com.streak.app.storage.AppRepository;
import com.streak.app.ui.MainActivity;

public class ReminderReceiver extends BroadcastReceiver {
    public static final String CHANNEL_ID = "habit_reminder_channel";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_CONTENT = "extra_content";
    public static final String EXTRA_NOTIFICATION_ID = "extra_notification_id";
    public static final String EXTRA_HABIT_ID = "extra_habit_id";

    @SuppressLint("MissingPermission")
    @Override
    public void onReceive(Context context, Intent intent) {
        String title = intent.getStringExtra(EXTRA_TITLE);
        String content = intent.getStringExtra(EXTRA_CONTENT);
        int notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 1001);
        long habitId = intent.getLongExtra(EXTRA_HABIT_ID, -1L);

        createChannel(context);

        Intent openIntent = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(isBlank(title) ? "习惯提醒" : "习惯提醒：" + title)
                .setContentText(isBlank(content) ? "该去完成今天的打卡了。" : content)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build());
        } catch (SecurityException ignored) {
        }

        rescheduleNextDay(context, habitId);
    }

    /**
     * 单次闹钟触发后，按数据库里的最新设置重排下一次（次日同一时间），
     * 实现“每日重复”。若习惯已被删除或关闭提醒，则不再重排。
     */
    private void rescheduleNextDay(Context context, long habitId) {
        if (habitId <= 0) {
            return;
        }
        try {
            AppRepository repository = new AppRepository(context);
            HabitItem habit = repository.findHabitById(habitId);
            if (habit != null && habit.isReminderEnabled()) {
                repository.syncReminder(habit);
            }
        } catch (Exception ignored) {
        }
    }

    private void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "习惯提醒",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("每日打卡提醒通知");
        manager.createNotificationChannel(channel);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
