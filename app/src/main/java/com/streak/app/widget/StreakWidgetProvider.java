package com.streak.app.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.streak.app.R;
import com.streak.app.model.HabitItem;
import com.streak.app.storage.AppRepository;
import com.streak.app.ui.MainActivity;
import com.streak.app.util.HabitUtils;

import java.util.List;

/**
 * 桌面小组件：显示今日打卡进度（已完成 / 习惯总数），点击进入 App。
 * 读数据涉及磁盘 IO，放后台线程，避免阻塞。
 */
public class StreakWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            renderAsync(context, appWidgetManager, id);
        }
    }

    /** 供 App 内数据变化后主动刷新所有小组件调用。 */
    public static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName cn = new ComponentName(context, StreakWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(cn);
        StreakWidgetProvider provider = new StreakWidgetProvider();
        provider.onUpdate(context, manager, ids);
    }

    private void renderAsync(Context context, AppWidgetManager manager, int widgetId) {
        final Context appContext = context.getApplicationContext();
        new Thread(() -> {
            int total = 0;
            int done = 0;
            try {
                AppRepository repository = new AppRepository(appContext);
                List<HabitItem> habits = repository.readHabits();
                String today = HabitUtils.today();
                total = habits.size();
                for (HabitItem item : habits) {
                    if (item.getCompletedDates() != null && item.getCompletedDates().contains(today)) {
                        done++;
                    }
                }
            } catch (Exception ignored) {
            }

            RemoteViews views = new RemoteViews(appContext.getPackageName(), R.layout.widget_streak);
            views.setTextViewText(R.id.tvWidgetProgress, done + " / " + total);
            boolean allDone = total > 0 && done == total;
            views.setTextViewText(R.id.tvWidgetAction, allDone ? "今日全勤 🎉" : "去打卡");

            // 整块 + 按钮都点击进入 App
            Intent openIntent = new Intent(appContext, MainActivity.class)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    appContext, 0, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent);
            views.setOnClickPendingIntent(R.id.tvWidgetAction, pendingIntent);

            manager.updateAppWidget(widgetId, views);
        }).start();
    }
}
