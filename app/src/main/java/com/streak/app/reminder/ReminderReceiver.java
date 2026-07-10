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

    @Override
    public void onReceive(Context context, Intent intent) {
        String title = intent.getStringExtra(EXTRA_TITLE);
        String content = intent.getStringExtra(EXTRA_CONTENT);
        int notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 1001);
        long habitId = intent.getLongExtra(EXTRA_HABIT_ID, -1L);

        createChannel(context);
        // 通知文案要根据「该习惯当前打卡状态」动态生成（断签补救 vs 日常提醒），
        // 需读 habits.json；连同重排下一次一起放后台线程，避开 onReceive ~10s 主线程限制。
        handleAsync(context, habitId, notificationId, title, content);
    }

    /**
     * 后台完成三件事：读取习惯最新状态、据此生成通知文案并发通知、重排次日闹钟。
     * 涉及磁盘 IO，用 goAsync() + 子线程执行。
     */
    @SuppressLint("MissingPermission")
    private void handleAsync(Context context, long habitId, int notificationId,
                             String fallbackTitle, String fallbackContent) {
        final Context appContext = context.getApplicationContext();
        final PendingResult pendingResult = goAsync();
        new Thread(() -> {
            try {
                HabitItem habit = null;
                if (habitId > 0) {
                    habit = new AppRepository(appContext).findHabitById(habitId);
                }

                String title = habit != null ? habit.getTitle() : fallbackTitle;
                String message = buildMessage(habit, fallbackContent);

                Intent openIntent = new Intent(appContext, MainActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent pendingIntent = PendingIntent.getActivity(
                        appContext,
                        notificationId,
                        openIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );

                NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle(isBlank(title) ? "习惯提醒" : "习惯提醒：" + title)
                        .setContentText(message)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent);

                try {
                    NotificationManagerCompat.from(appContext).notify(notificationId, builder.build());
                } catch (SecurityException ignored) {
                }

                // 重排下一次（次日同一时间），实现每日重复；已删除/关提醒则不再排
                if (habit != null && habit.isReminderEnabled()) {
                    new AppRepository(appContext).syncReminder(habit);
                }
            } catch (Exception ignored) {
            } finally {
                pendingResult.finish();
            }
        }).start();
    }

    /**
     * 生成温和的提醒文案：
     * - 昨天该打却没打（断签）：补救语气，鼓励别中断连续记录；
     * - 今天已打卡：轻提示已完成；
     * - 其余：日常提醒。
     */
    private String buildMessage(HabitItem habit, String fallbackContent) {
        if (habit == null) {
            return isBlank(fallbackContent) ? "该去完成今天的打卡了。" : fallbackContent;
        }
        java.util.List<String> dates = habit.getCompletedDates();
        String today = java.time.LocalDate.now().toString();
        String yesterday = java.time.LocalDate.now().minusDays(1).toString();
        boolean doneToday = dates != null && dates.contains(today);
        boolean doneYesterday = dates != null && dates.contains(yesterday);

        if (doneToday) {
            return "今天已经打过卡啦，继续保持这份坚持 👏";
        }
        if (!doneYesterday) {
            // 昨天断了：温和补救，不指责
            return "昨天断签了，别灰心，今天补上一笔，重新开始也很棒 💪";
        }
        int streak = com.streak.app.util.HabitUtils.currentStreak(dates);
        if (streak > 0) {
            return "已经连续坚持 " + streak + " 天，今天打卡别让它中断哦 🔥";
        }
        return isBlank(fallbackContent) ? "该去完成今天的打卡了。" : fallbackContent;
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
