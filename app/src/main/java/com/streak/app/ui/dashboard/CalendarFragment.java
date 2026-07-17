package com.streak.app.ui.dashboard;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.streak.app.R;
import com.streak.app.databinding.ItemSheetHabitBinding;
import com.streak.app.databinding.ItemStatRowBinding;
import com.streak.app.databinding.SheetCalendarDetailBinding;
import com.streak.app.databinding.ViewDashboardCalendarBinding;
import com.streak.app.model.CalendarCell;
import com.streak.app.model.HabitItem;
import com.streak.app.util.HabitUtils;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 日历页（Phase C 从 MainActivity 拆出）。展示月历（打卡高亮）、连续榜、
 * 以及点击某天弹出的当天打卡详情弹层（支持过去日期补卡/撤销）。
 *
 * <p>数据观测 {@link DashboardViewModel#getHabits()}；补卡/撤销后调
 * {@link DashboardViewModel#reload()} 让全体页面随 LiveData 刷新，取代旧的手动逐页 update。
 * {@code displayedMonth}（翻月锚点）是本页私有状态，null 表示当月。</p>
 */
public class CalendarFragment extends Fragment {

    private ViewDashboardCalendarBinding binding;
    private DashboardViewModel viewModel;

    private final List<HabitItem> habits = new ArrayList<>();
    // 当前显示月份锚点（该月任意一天）；null 表示当月
    private String displayedMonth;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = ViewDashboardCalendarBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(DashboardViewModel.class);

        binding.btnCalendarPrev.setOnClickListener(v -> shiftCalendarMonth(-1));
        binding.btnCalendarNext.setOnClickListener(v -> shiftCalendarMonth(1));
        binding.btnCalendarToday.setOnClickListener(v -> {
            displayedMonth = null; // 复位到当月
            render();
        });

        viewModel.getHabits().observe(getViewLifecycleOwner(), list -> {
            habits.clear();
            if (list != null) {
                habits.addAll(list);
            }
            render();
        });
    }

    /** 宿主切到日历标签时调用：复位到当月并重绘（避免停留在上次翻到的历史月份）。 */
    public void resetToCurrentMonth() {
        if (displayedMonth != null) {
            displayedMonth = null;
            render();
        }
    }

    private void render() {
        if (binding == null) {
            return;
        }
        String today = HabitUtils.today();
        String anchor = displayedMonth != null ? displayedMonth : today;
        binding.tvCalendarMonth.setText(
                LocalDate.parse(anchor).format(DateTimeFormatter.ofPattern("yyyy 年 MM 月", Locale.CHINA))
        );
        boolean viewingCurrentMonth = YearMonth.from(LocalDate.parse(anchor))
                .equals(YearMonth.from(LocalDate.parse(today)));
        binding.btnCalendarToday.setVisibility(viewingCurrentMonth ? View.GONE : View.VISIBLE);

        Set<String> completedSet = new HashSet<>();
        for (HabitItem item : habits) {
            completedSet.addAll(item.getCompletedDates());
        }
        List<CalendarCell> cells = HabitUtils.buildMonthCells(anchor, today, completedSet);
        binding.gridCalendar.removeAllViews();
        int cellSize = (int) (40 * getResources().getDisplayMetrics().density);
        for (CalendarCell cell : cells) {
            TextView textView = new TextView(requireContext());
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = cellSize;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            textView.setLayoutParams(params);
            textView.setGravity(Gravity.CENTER);
            textView.setText(cell.isEmpty() ? "" : String.valueOf(cell.getDay()));
            textView.setTextColor(ContextCompat.getColor(requireContext(), R.color.streak_text_primary));
            if (cell.isToday()) {
                textView.setBackgroundResource(R.drawable.bg_calendar_today);
                textView.setTextColor(ContextCompat.getColor(requireContext(), R.color.streak_on_primary));
            } else if (cell.isCompleted()) {
                textView.setBackgroundResource(R.drawable.bg_calendar_completed);
                textView.setTextColor(ContextCompat.getColor(requireContext(), R.color.streak_accent));
            } else {
                textView.setBackgroundResource(R.drawable.bg_calendar_default);
                textView.setTextColor(ContextCompat.getColor(requireContext(), R.color.streak_text_faint));
            }
            if (!cell.isEmpty()) {
                textView.setOnClickListener(v -> showCalendarDetailDialog(cell.getDate()));
            }
            binding.gridCalendar.addView(textView);
        }

        binding.layoutRankingContainer.removeAllViews();
        if (habits.isEmpty()) {
            binding.tvCalendarEmpty.setVisibility(View.VISIBLE);
            return;
        }
        binding.tvCalendarEmpty.setVisibility(View.GONE);
        List<HabitItem> ranking = new ArrayList<>(habits);
        ranking.sort((a, b) -> Integer.compare(
                HabitUtils.currentStreak(b.getCompletedDates()),
                HabitUtils.currentStreak(a.getCompletedDates())
        ));
        int limit = Math.min(5, ranking.size());
        for (int i = 0; i < limit; i++) {
            HabitItem item = ranking.get(i);
            ItemStatRowBinding rowBinding = ItemStatRowBinding.inflate(
                    getLayoutInflater(), binding.layoutRankingContainer, false);
            rowBinding.tvStatLabel.setText((i + 1) + ". " + item.getTitle() + " · " + item.getCategory());
            rowBinding.tvStatValue.setText(getString(R.string.stat_streak_days,
                    HabitUtils.currentStreak(item.getCompletedDates())));
            binding.layoutRankingContainer.addView(rowBinding.getRoot());
        }
    }

    /** 日历翻月：delta 为 -1（上月）或 +1（下月）。 */
    private void shiftCalendarMonth(int delta) {
        String anchor = displayedMonth != null ? displayedMonth : HabitUtils.today();
        try {
            LocalDate shifted = LocalDate.parse(anchor).plusMonths(delta).withDayOfMonth(1);
            displayedMonth = shifted.toString();
        } catch (Exception ignored) {
            displayedMonth = null;
        }
        render();
    }

    private void showCalendarDetailDialog(String date) {
        SheetCalendarDetailBinding sheetBinding = SheetCalendarDetailBinding.inflate(getLayoutInflater());
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        dialog.setContentView(sheetBinding.getRoot());
        populateCalendarSheet(sheetBinding, date);
        dialog.show();
    }

    private void populateCalendarSheet(SheetCalendarDetailBinding sheetBinding, String date) {
        boolean isPast = date.compareTo(HabitUtils.today()) < 0;

        List<HabitItem> completed = new ArrayList<>();
        List<HabitItem> pending = new ArrayList<>();
        for (HabitItem item : habits) {
            if (item.getCompletedDates().contains(date)) {
                completed.add(item);
            } else {
                pending.add(item);
            }
        }

        sheetBinding.tvSheetDate.setText(getString(R.string.sheet_date_title, date));
        sheetBinding.tvSheetSummary.setText(
                getString(R.string.sheet_summary, completed.size(), pending.size())
        );

        ViewGroup container = sheetBinding.layoutSheetContent;
        container.removeAllViews();

        if (completed.isEmpty() && pending.isEmpty()) {
            addSheetSectionTitle(container, getString(R.string.sheet_empty));
        } else {
            if (!pending.isEmpty()) {
                addSheetSectionTitle(container, getString(R.string.sheet_section_pending));
                for (HabitItem item : pending) {
                    addSheetHabitRow(container, sheetBinding, item, date, false, isPast);
                }
            }
            if (!completed.isEmpty()) {
                addSheetSectionTitle(container, getString(R.string.sheet_section_done));
                for (HabitItem item : completed) {
                    addSheetHabitRow(container, sheetBinding, item, date, true, isPast);
                }
            }
        }
    }

    private void addSheetSectionTitle(ViewGroup container, String title) {
        TextView textView = new TextView(requireContext());
        textView.setText(title);
        textView.setTextColor(ContextCompat.getColor(requireContext(), R.color.streak_muted));
        textView.setTextSize(13f);
        int top = (int) (12 * getResources().getDisplayMetrics().density);
        textView.setPadding(0, top, 0, 0);
        container.addView(textView);
    }

    private void addSheetHabitRow(ViewGroup container, SheetCalendarDetailBinding sheetBinding,
                                  HabitItem item, String date, boolean completed, boolean isPast) {
        ItemSheetHabitBinding rowBinding = ItemSheetHabitBinding.inflate(getLayoutInflater(), container, false);
        rowBinding.tvSheetHabitTitle.setText(item.getTitle());
        rowBinding.viewSheetDot.setBackgroundResource(
                completed ? R.drawable.bg_status_done : R.drawable.bg_status_pending
        );
        rowBinding.tvSheetHabitStatus.setText(completed ? getString(R.string.status_completed) : getString(R.string.status_pending));
        rowBinding.tvSheetHabitStatus.setTextColor(
                ContextCompat.getColor(requireContext(), completed ? R.color.streak_accent : R.color.streak_muted)
        );

        if (isPast) {
            rowBinding.btnSheetToggle.setVisibility(View.VISIBLE);
            rowBinding.btnSheetToggle.setText(completed ? getString(R.string.action_undo) : getString(R.string.action_makeup));
            rowBinding.btnSheetToggle.setOnClickListener(v -> {
                // 原地更新数据并重建弹窗内容，支持连续补卡，不关闭弹窗。
                toggleDateCheckIn(item.getId(), date, !completed);
                // 内存态即时更新本地列表里这条习惯，弹窗重建即可反映；全局刷新走 viewModel.reload()
                for (HabitItem h : habits) {
                    if (h.getId() == item.getId()) {
                        List<String> dates = new ArrayList<>(h.getCompletedDates());
                        if (!completed) {
                            if (!dates.contains(date)) dates.add(date);
                        } else {
                            dates.remove(date);
                        }
                        h.setCompletedDates(dates);
                        break;
                    }
                }
                populateCalendarSheet(sheetBinding, date);
            });
        }

        container.addView(rowBinding.getRoot());

        // 已完成且当天有备注/心情：在行下方追加一行灰字展示
        if (completed) {
            String note = item.getNote(date);
            if (!note.isEmpty()) {
                TextView noteView = new TextView(requireContext());
                noteView.setText("“" + note + "”");
                noteView.setTextColor(ContextCompat.getColor(requireContext(), R.color.streak_muted));
                noteView.setTextSize(13f);
                int start = (int) (22 * getResources().getDisplayMetrics().density);
                noteView.setPadding(start, 0, 0, (int) (6 * getResources().getDisplayMetrics().density));
                container.addView(noteView);
            }
        }
    }

    /** 过去某天补卡/撤销：直写记录表（按 id，不整表覆盖），再让共享数据刷新全体页面。 */
    private void toggleDateCheckIn(long habitId, String date, boolean add) {
        com.streak.app.util.AppExecutors.getInstance().diskIO().execute(() -> {
            if (add) {
                com.streak.app.model.CheckInRecord existing =
                        viewModel.repository().getCheckIn(habitId, date);
                int mood = existing == null ? 0 : existing.getMood();
                int duration = existing == null ? 0 : existing.getDurationMinutes();
                String note = existing == null ? null : existing.getNote();
                String photo = existing == null ? null : existing.getPhotoUri();
                viewModel.repository().upsertCheckIn(habitId, date, mood, duration, note, photo);
            } else {
                viewModel.repository().removeCheckIn(habitId, date);
            }
            com.streak.app.widget.StreakWidgetProvider.refreshAll(requireContext().getApplicationContext());
            viewModel.reload();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
