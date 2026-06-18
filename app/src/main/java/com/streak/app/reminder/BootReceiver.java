package com.streak.app.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.streak.app.storage.AppRepository;

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
        try {
            new AppRepository(context).rescheduleAllReminders();
        } catch (Exception ignored) {
        }
    }
}
