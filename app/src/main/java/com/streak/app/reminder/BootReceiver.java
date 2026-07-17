package com.streak.app.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.streak.app.storage.AppRepository;
import com.streak.app.util.AppExecutors;

/**
 * 设备重启后，AlarmManager 里的闹钟会被清空。
 * 监听 BOOT_COMPLETED，重新为所有开启提醒的习惯调度闹钟。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        // 读取全部习惯并重排闹钟，放后台线程，避免在 onReceive 主线程做文件 IO
        final Context appContext = context.getApplicationContext();
        final PendingResult pendingResult = goAsync();
        // 走应用级 diskIO 池（单线程串行），取代零散 new Thread()——见 AppExecutors。
        AppExecutors.getInstance().diskIO().execute(() -> {
            try {
                new AppRepository(appContext).rescheduleAllReminders();
            } catch (Exception ignored) {
            } finally {
                pendingResult.finish();
            }
        });
    }
}
