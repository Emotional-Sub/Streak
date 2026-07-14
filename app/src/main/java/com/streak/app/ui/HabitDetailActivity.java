package com.streak.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.streak.app.R;
import com.streak.app.databinding.ActivityHabitDetailBinding;
import com.streak.app.databinding.ItemDetailNoteBinding;
import com.streak.app.model.HabitItem;
import com.streak.app.storage.AppRepository;
import com.streak.app.util.HabitAnalytics;
import com.streak.app.util.HabitUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 习惯详情页：单个习惯的专属视图——关键数字（当前/最长连续、累计打卡）、
 * 本习惯热力图、轻量分析（星期偏好/断卡预警）、以及打卡备注时间线。
 * 全部基于既有数据只读展示，不改动任何习惯。
 */
public class HabitDetailActivity extends AppCompatActivity {

    public static final String EXTRA_HABIT_ID = "extra_habit_id";

    private ActivityHabitDetailBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityHabitDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbarHabitDetail.setNavigationOnClickListener(v -> finish());

        long habitId = getIntent().getLongExtra(EXTRA_HABIT_ID, -1L);
        AppRepository repository = new AppRepository(this);
        HabitItem habit = habitId > 0 ? repository.findHabitById(habitId) : null;
        if (habit == null) {
            // 习惯不存在（可能已被删）：提示并退出，避免空指针
            android.widget.Toast.makeText(this, R.string.toast_habit_not_found,
                    android.widget.Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        bindHeader(habit);
        bindStreakNumbers(habit);
        binding.detailHeatmap.setData(buildHeatmapCounts(habit));
        bindInsights(habit);
        bindNotes(habit);
    }

    private void bindHeader(HabitItem habit) {
        binding.tvDetailTitle.setText(habit.getTitle());
        binding.tvDetailContent.setText(habit.getContent());
        String meta = habit.isWeeklyGoal()
                ? getString(R.string.habit_detail_meta_weekly, habit.getCategory(),
                    habit.getWeeklyTarget(), HabitUtils.weeklyDoneCount(habit), habit.getReminderTime())
                : getString(R.string.habit_detail_meta_daily, habit.getCategory(), habit.getReminderTime());
        binding.tvDetailMeta.setText(meta);
    }

    private void bindStreakNumbers(HabitItem habit) {
        binding.tvDetailCurrentStreak.setText(
                String.valueOf(HabitUtils.currentStreak(habit.getCompletedDates())));
        binding.tvDetailLongestStreak.setText(
                String.valueOf(HabitUtils.longestStreak(habit.getCompletedDates())));
        binding.tvDetailTotalCheckIns.setText(
                String.valueOf(HabitUtils.uniqueCheckIns(habit)));
    }

    /** 把本习惯的去重打卡日期聚合成「日期 -> 1」，喂给热力图（单习惯每天最多算一次）。 */
    private Map<String, Integer> buildHeatmapCounts(HabitItem habit) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        if (habit.getCompletedDates() != null) {
            for (String date : new HashSet<>(habit.getCompletedDates())) {
                if (date != null) {
                    counts.put(date, 1);
                }
            }
        }
        return counts;
    }

    private void bindInsights(HabitItem habit) {
        LinearLayout container = binding.layoutDetailInsights;
        container.removeAllViews();

        List<HabitItem> single = Collections.singletonList(habit);
        int activeWeekday = HabitAnalytics.mostActiveWeekday(single);
        if (activeWeekday > 0) {
            int[] buckets = HabitAnalytics.checkInsByWeekday(single);
            addLine(container, getString(R.string.habit_detail_insight_active_weekday,
                    weekdayName(activeWeekday), buckets[activeWeekday - 1]));
        }

        int gap = HabitAnalytics.daysSinceLastCheckIn(habit);
        if (gap == Integer.MAX_VALUE) {
            addLine(container, getString(R.string.habit_detail_insight_never));
        } else if (gap >= HabitAnalytics.STALE_WARNING_DAYS) {
            addLine(container, getString(R.string.habit_detail_insight_stale, gap));
        } else {
            addLine(container, getString(R.string.habit_detail_insight_on_track));
        }
    }

    /**
     * 打卡备注时间线：按日期倒序（最近在上）逐条展示日期 + 备注文本。
     * 无任何备注时显示占位提示。
     */
    private void bindNotes(HabitItem habit) {
        LinearLayout container = binding.layoutDetailNotes;
        container.removeAllViews();
        Map<String, String> notes = habit.getNotes();
        if (notes == null || notes.isEmpty()) {
            binding.tvDetailNotesEmpty.setVisibility(View.VISIBLE);
            return;
        }
        binding.tvDetailNotesEmpty.setVisibility(View.GONE);
        List<String> dates = new ArrayList<>(notes.keySet());
        // 日期字符串 yyyy-MM-dd 字典序即时间序，倒序得到最近在前
        Collections.sort(dates, Collections.reverseOrder());
        for (String date : dates) {
            String note = notes.get(date);
            if (note == null || note.trim().isEmpty()) {
                continue;
            }
            addNoteRow(container, date, note);
        }
    }

    private void addLine(LinearLayout container, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.streak_text_secondary));
        tv.setTextSize(14f);
        int pad = (int) (6 * getResources().getDisplayMetrics().density);
        tv.setPadding(0, pad, 0, pad);
        container.addView(tv);
    }

    private void addNoteRow(LinearLayout container, String date, String note) {
        ItemDetailNoteBinding row = ItemDetailNoteBinding.inflate(getLayoutInflater(), container, false);
        row.tvNoteDate.setText(date);
        row.tvNoteText.setText(note);
        container.addView(row.getRoot());
    }

    /** 把 DayOfWeek 值（1=周一 ... 7=周日）映射成本地化星期名。 */
    private String weekdayName(int isoDay) {
        switch (isoDay) {
            case 1: return getString(R.string.weekday_monday);
            case 2: return getString(R.string.weekday_tuesday);
            case 3: return getString(R.string.weekday_wednesday);
            case 4: return getString(R.string.weekday_thursday);
            case 5: return getString(R.string.weekday_friday);
            case 6: return getString(R.string.weekday_saturday);
            case 7: return getString(R.string.weekday_sunday);
            default: return "";
        }
    }

    /** 供 MainActivity 构造跳转 Intent。 */
    public static Intent newIntent(android.content.Context context, long habitId) {
        Intent intent = new Intent(context, HabitDetailActivity.class);
        intent.putExtra(EXTRA_HABIT_ID, habitId);
        return intent;
    }
}
