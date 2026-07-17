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
import com.streak.app.util.AppExecutors;
import com.streak.app.util.AppExecutors;

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
        // 走应用级 diskIO 池（单线程串行），取代零散 new Thread()——见 AppExecutors。
        AppExecutors.getInstance().diskIO().execute(() -> {
            try {
                HabitItem habit = null;
                if (habitId > 0) {
                    habit = new AppRepository(appContext).findHabitById(habitId);
                    // 针对具体习惯的闹钟，但该习惯已不存在（被删除或导入备份整表替换掉）：
                    // 这是一条本不该再触发的陈旧闹钟，直接静默返回，不用旧文案发一条误导通知。
                    if (habit == null) {
                        return;
                    }
                }

                String title = habit != null ? habit.getTitle() : fallbackTitle;
                String message = buildMessage(appContext, habit, fallbackContent);

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
                        .setContentTitle(isBlank(title) ? appContext.getString(R.string.notif_title_default)
                                : appContext.getString(R.string.notif_title_with_habit, title))
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
        });
    }

    /**
     * 生成温和的提醒文案，按习惯周期口径（与列表/统计一致，滚动 7 天窗口）：
     * - 每周 N 次型且本周已达标：报喜，不催打卡；未达标则显示进度；
     * - 每天型：今天已打卡轻提示；昨天断签温和补救；否则按连续天数鼓励。
     */
    private String buildMessage(Context context, HabitItem habit, String fallbackContent) {
        if (habit == null) {
            return isBlank(fallbackContent) ? context.getString(R.string.reminder_default) : fallbackContent;
        }
        java.util.List<String> dates = habit.getCompletedDates();
        String today = java.time.LocalDate.now().toString();
        String yesterday = java.time.LocalDate.now().minusDays(1).toString();
        boolean doneToday = dates != null && dates.contains(today);
        boolean doneYesterday = dates != null && dates.contains(yesterday);

        // 每周 N 次型：按本周（滚动 7 天）达标进度决定文案，不再逢天必催。
        if (habit.isWeeklyGoal()) {
            int done = com.streak.app.util.HabitUtils.weeklyDoneCount(habit);
            int target = habit.getWeeklyTarget();
            if (done >= target) {
                return context.getString(R.string.reminder_weekly_done, done, target);
            }
            if (doneToday) {
                return context.getString(R.string.reminder_weekly_today_done, done, target);
            }
            return context.getString(R.string.reminder_weekly_progress, target - done, done, target);
        }

        if (doneToday) {
            return context.getString(R.string.reminder_daily_today_done);
        }
        if (!doneYesterday) {
            // 昨天断了：温和补救，不指责
            return context.getString(R.string.reminder_daily_missed_yesterday);
        }
        int streak = com.streak.app.util.HabitUtils.currentStreak(dates);
        if (streak > 0) {
            return context.getString(R.string.reminder_daily_streak, streak);
        }
        return isBlank(fallbackContent) ? context.getString(R.string.reminder_default) : fallbackContent;
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
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription(context.getString(R.string.notif_channel_desc));
        manager.createNotificationChannel(channel);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
