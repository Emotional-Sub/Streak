package com.streak.app.ui.dashboard;

import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.chip.Chip;
import com.streak.app.R;
import com.streak.app.databinding.ItemStatRowBinding;
import com.streak.app.databinding.ViewDashboardStatsBinding;
import com.streak.app.model.HabitItem;
import com.streak.app.ui.CategoryPieChart;
import com.streak.app.util.HabitAnalytics;
import com.streak.app.util.HabitUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 统计页（Phase C 从 MainActivity 拆出）。只观测 {@link DashboardViewModel#getHabits()}，
 * 数据变化时自更新本页——概览数字、热力图、周期统计、智能洞察、分类饼图。
 *
 * <p>本页只读、不改数据，故不持有仓库；共享 ViewModel 挂在宿主 Activity 作用域。
 * summary box 的四个数字 TextView 由 {@code <include>} 布局提供，沿用 findViewById 绑定
 * （与旧 MainActivity 一致，避免额外的 binding 类）。</p>
 */
public class StatsFragment extends Fragment {

    private ViewDashboardStatsBinding binding;
    private DashboardViewModel viewModel;

    private TextView tvStatsHabitCount;
    private TextView tvStatsTotalCheckIns;
    private TextView tvStatsBestStreak;
    private TextView tvStatsCompletionRate;

    private final List<HabitItem> habits = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = ViewDashboardStatsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tvStatsHabitCount = view.findViewById(R.id.tvStatsHabitCount);
        tvStatsTotalCheckIns = view.findViewById(R.id.tvStatsTotalCheckIns);
        tvStatsBestStreak = view.findViewById(R.id.tvStatsBestStreak);
        tvStatsCompletionRate = view.findViewById(R.id.tvStatsCompletionRate);

        viewModel = new ViewModelProvider(requireActivity()).get(DashboardViewModel.class);
        viewModel.getHabits().observe(getViewLifecycleOwner(), list -> {
            habits.clear();
            if (list != null) {
                habits.addAll(list);
            }
            render();
        });
    }

    /** 进入本页（宿主切到统计标签）时重放饼图动画。 */
    public void replayPie() {
        if (binding != null) {
            binding.pieCategory.replay();
        }
    }

    private void render() {
        int totalCheckIns = HabitUtils.totalCheckIns(habits);
        int currentBest = HabitUtils.bestCurrentStreak(habits);
        int longestBest = HabitUtils.bestLongestStreak(habits);
        tvStatsHabitCount.setText(String.valueOf(habits.size()));
        tvStatsTotalCheckIns.setText(String.valueOf(totalCheckIns));
        tvStatsBestStreak.setText(getString(R.string.stat_streak_days_nospace, currentBest));
        tvStatsCompletionRate.setText(HabitUtils.completionRate(habits) + "%");

        binding.heatmap.setData(buildHeatmapCounts());

        binding.layoutOverviewStats.removeAllViews();
        addStatRow(binding.layoutOverviewStats, getString(R.string.stat_overview_current_vs_longest_label),
                getString(R.string.stat_overview_current_vs_longest_value, currentBest, longestBest));
        int thisWeek = HabitUtils.weeklyCheckIns(habits);
        int lastWeek = HabitUtils.lastWeekCheckIns(habits);
        addStatRow(binding.layoutOverviewStats, getString(R.string.stat_last7_checkins_label),
                thisWeek + "  " + weekTrendText(thisWeek, lastWeek));
        addStatRow(binding.layoutOverviewStats, getString(R.string.stat_month_checkins_label),
                String.valueOf(HabitUtils.monthlyCheckIns(habits)));
        int reminderCount = 0;
        for (HabitItem item : habits) {
            if (item.isReminderEnabled()) {
                reminderCount++;
            }
        }
        addStatRow(binding.layoutOverviewStats, getString(R.string.stat_reminder_enabled_label),
                String.valueOf(reminderCount));

        updateInsights();

        binding.layoutCategoryStats.removeAllViews();
        if (habits.isEmpty()) {
            binding.tvEmptyStats.setVisibility(View.VISIBLE);
            binding.pieCategory.setVisibility(View.GONE);
            binding.chipGroupPieLegend.setVisibility(View.GONE);
            return;
        }
        binding.tvEmptyStats.setVisibility(View.GONE);
        updateCategoryPie();
        List<String> categories = HabitUtils.categories();
        for (String category : categories) {
            if ("全部".equals(category)) {
                continue;
            }
            int count = 0;
            for (HabitItem item : habits) {
                if (category.equals(item.getCategory())) {
                    count += HabitUtils.uniqueCheckIns(item);
                }
            }
            if (count > 0) {
                addStatRow(binding.layoutCategoryStats, category, String.valueOf(count));
            }
        }
    }

    /** 把所有习惯的去重打卡日期聚合成「日期 -> 次数」，喂给热力图。 */
    private java.util.Map<String, Integer> buildHeatmapCounts() {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (HabitItem item : habits) {
            if (item.getCompletedDates() == null) {
                continue;
            }
            for (String date : new HashSet<>(item.getCompletedDates())) {
                if (date == null) {
                    continue;
                }
                Integer prev = counts.get(date);
                counts.put(date, prev == null ? 1 : prev + 1);
            }
        }
        return counts;
    }

    /** 周环比文案：↑N / ↓N / 持平。 */
    private String weekTrendText(int thisWeek, int lastWeek) {
        int diff = thisWeek - lastWeek;
        if (diff > 0) {
            return getString(R.string.weekly_compare_up, diff);
        }
        if (diff < 0) {
            return getString(R.string.weekly_compare_down, (-diff));
        }
        return getString(R.string.weekly_compare_equal);
    }

    private void updateCategoryPie() {
        List<CategoryPieChart.Slice> slices = new ArrayList<>();
        int totalHabits = 0;
        for (String category : HabitUtils.categories()) {
            if ("全部".equals(category)) {
                continue;
            }
            int count = 0;
            for (HabitItem item : habits) {
                if (category.equals(item.getCategory())) {
                    count++;
                }
            }
            if (count > 0) {
                int color = ContextCompat.getColor(requireContext(), categoryColor(category));
                slices.add(new CategoryPieChart.Slice(category, count, color));
                totalHabits += count;
            }
        }

        binding.pieCategory.setVisibility(View.VISIBLE);
        binding.chipGroupPieLegend.setVisibility(View.VISIBLE);
        binding.pieCategory.setData(slices, String.valueOf(totalHabits));

        binding.chipGroupPieLegend.removeAllViews();
        for (CategoryPieChart.Slice slice : slices) {
            int percent = totalHabits == 0 ? 0 : Math.round(slice.value * 100f / totalHabits);
            Chip chip = new Chip(requireContext());
            chip.setText(slice.label + " " + percent + "%");
            chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.streak_surface_alt)));
            chip.setChipStrokeWidth(0f);
            chip.setChipIconVisible(true);
            chip.setChipIcon(new ColorDrawable(slice.color));
            chip.setEnsureMinTouchTargetSize(false);
            chip.setClickable(false);
            binding.chipGroupPieLegend.addView(chip);
        }
    }

    private int categoryColor(String category) {
        switch (category) {
            case "学习":
                return R.color.cat_study;
            case "运动":
                return R.color.cat_sport;
            case "生活":
                return R.color.cat_life;
            case "工作":
                return R.color.cat_work;
            case "阅读":
                return R.color.cat_read;
            default:
                return R.color.streak_primary;
        }
    }

    private void addStatRow(ViewGroup container, String label, String value) {
        ItemStatRowBinding rowBinding = ItemStatRowBinding.inflate(getLayoutInflater(), container, false);
        rowBinding.tvStatLabel.setText(label);
        rowBinding.tvStatValue.setText(value);
        container.addView(rowBinding.getRoot());
    }

    /**
     * 填充「智能洞察」卡片：最能坚持的习惯、打卡最活跃的星期几、断卡预警。
     * 全部基于 {@link HabitAnalytics} 聚合，无额外存储。无习惯时隐藏整张卡片。
     */
    private void updateInsights() {
        binding.layoutInsights.removeAllViews();
        if (habits.isEmpty()) {
            binding.cardInsights.setVisibility(View.GONE);
            return;
        }
        binding.cardInsights.setVisibility(View.VISIBLE);

        HabitItem consistent = HabitAnalytics.mostConsistent(habits);
        if (consistent != null) {
            int longest = HabitUtils.longestStreak(consistent.getCompletedDates());
            addStatRow(binding.layoutInsights,
                    getString(R.string.insight_most_consistent_label),
                    getString(R.string.insight_most_consistent_value, consistent.getTitle(), longest));
        }

        int weekday = HabitAnalytics.mostActiveWeekday(habits);
        if (weekday > 0) {
            int[] buckets = HabitAnalytics.checkInsByWeekday(habits);
            addStatRow(binding.layoutInsights,
                    getString(R.string.insight_active_weekday_label),
                    getString(R.string.insight_active_weekday_value,
                            weekdayName(weekday), buckets[weekday - 1]));
        }

        List<HabitItem> stale = HabitAnalytics.staleHabits(habits);
        if (stale.isEmpty()) {
            addStatRow(binding.layoutInsights,
                    getString(R.string.insight_stale_label),
                    getString(R.string.insight_all_on_track));
        } else {
            HabitItem worst = stale.get(0);
            int gap = HabitAnalytics.daysSinceLastCheckIn(worst);
            String value = gap == Integer.MAX_VALUE
                    ? getString(R.string.insight_stale_never_value, worst.getTitle())
                    : getString(R.string.insight_stale_value, worst.getTitle(), gap);
            addStatRow(binding.layoutInsights,
                    getString(R.string.insight_stale_label), value);
        }
    }

    /** 星期序号(1=周一 ... 7=周日，对齐 DayOfWeek)转本地化名称。越界返回空串。 */
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
