package com.streak.app.data;

import android.content.Context;

import com.streak.app.model.HabitItem;
import com.streak.app.reminder.ReminderScheduler;

import java.util.List;

/**
 * 提醒调度封装（Phase B 从 {@code AppRepository} 拆出）。
 *
 * <p><b>职责边界。</b>只负责单个习惯提醒的调度/取消，以及批量重排（开机恢复、登录后恢复）。
 * 不读写习惯数据——批量重排所需的习惯列表由调用方（{@code AppRepository}）传入，
 * 本类不依赖任何 Repository，避免与数据层循环耦合。</p>
 */
public class ReminderManager {

    private final ReminderScheduler reminderScheduler;

    public ReminderManager(Context context) {
        this.reminderScheduler = new ReminderScheduler(context.getApplicationContext());
    }

    /** 调度（或按最新设置重排）单个习惯的提醒。 */
    public void schedule(HabitItem habit) {
        reminderScheduler.schedule(habit);
    }

    /** 取消单个习惯的提醒闹钟。 */
    public void cancel(long habitId) {
        reminderScheduler.cancel(habitId);
    }

    /**
     * 重新调度给定习惯里所有开启提醒的项，用于开机后/登录后恢复闹钟。
     * 单个习惯调度失败不中断其余（避免一条脏数据让全部提醒丢失）。
     */
    public void scheduleAll(List<HabitItem> habits) {
        if (habits == null) {
            return;
        }
        for (HabitItem habit : habits) {
            if (habit != null && habit.isReminderEnabled()) {
                try {
                    reminderScheduler.schedule(habit);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 取消给定习惯的全部提醒（退出/切换账号/删号时用）。 */
    public void cancelAll(List<HabitItem> habits) {
        if (habits == null) {
            return;
        }
        for (HabitItem habit : habits) {
            if (habit != null) {
                reminderScheduler.cancel(habit.getId());
            }
        }
    }
}
