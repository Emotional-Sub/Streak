package com.streak.app.ui.dashboard;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.streak.app.R;
import com.streak.app.StreakApp;
import com.streak.app.databinding.ItemBadgeBinding;
import com.streak.app.databinding.ItemStatRowBinding;
import com.streak.app.databinding.ViewDashboardProfileBinding;
import com.streak.app.model.Badge;
import com.streak.app.model.HabitItem;
import com.streak.app.model.UserAccount;
import com.streak.app.storage.AppRepository;
import com.streak.app.ui.BadgeWallActivity;
import com.streak.app.ui.ProfileEditActivity;
import com.streak.app.ui.ShareReportActivity;
import com.streak.app.util.AppExecutors;
import com.streak.app.util.AvatarPresets;
import com.streak.app.util.BadgeUtils;
import com.streak.app.util.HabitUtils;
import com.streak.app.util.ImageLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「我的」页（Phase C 从 MainActivity 拆出）。观测 {@link DashboardViewModel} 的习惯与展示名，
 * 渲染个人资料、汇总数字、最佳习惯、分类统计、勋章预览；并承载编辑资料/删号/分享战报/主题切换交互。
 *
 * <p>删号后需跳回登录页，但本页不硬编码目标 Activity——通过 {@link DashboardHost} 回调交宿主处理，
 * 保持 Fragment 与具体宿主解耦。编辑资料返回后调 {@code viewModel.reload()} 刷新展示名/头像。</p>
 */
public class ProfileFragment extends Fragment {

    private ViewDashboardProfileBinding binding;
    private DashboardViewModel viewModel;
    private AppRepository repository;

    private android.widget.TextView tvProfileHabitCount;
    private android.widget.TextView tvProfileCheckInCount;
    private android.widget.TextView tvProfileTodayCount;
    private android.widget.TextView tvProfileBestStreak;

    private androidx.activity.result.ActivityResultLauncher<Intent> profileEditLauncher;

    private final List<HabitItem> habits = new ArrayList<>();
    private String displayName = "";

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        profileEditLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                result -> viewModel.reload());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = ViewDashboardProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tvProfileHabitCount = view.findViewById(R.id.tvProfileHabitCount);
        tvProfileCheckInCount = view.findViewById(R.id.tvProfileCheckInCount);
        tvProfileTodayCount = view.findViewById(R.id.tvProfileTodayCount);
        tvProfileBestStreak = view.findViewById(R.id.tvProfileBestStreak);

        viewModel = new ViewModelProvider(requireActivity()).get(DashboardViewModel.class);
        repository = viewModel.repository();

        binding.btnEditProfile.setOnClickListener(v ->
                profileEditLauncher.launch(new Intent(requireContext(), ProfileEditActivity.class)));
        binding.btnDeleteAccount.setOnClickListener(v -> confirmDeleteAccount());
        binding.btnShareReport.setOnClickListener(v -> shareAchievementCard());
        binding.btnThemeMode.setOnClickListener(v -> showThemeModeChooser());
        updateThemeModeButtonText();
        binding.cardBadgeWall.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), BadgeWallActivity.class)));

        viewModel.getDisplayName().observe(getViewLifecycleOwner(), name -> {
            displayName = name == null ? "" : name;
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

    private void render() {
        String today = HabitUtils.today();
        UserAccount account = repository.getCurrentAccount();
        String motto = getString(R.string.profile_motto);
        String avatarUri = null;
        if (account != null) {
            if (account.getMotto() != null && !account.getMotto().trim().isEmpty()) {
                motto = account.getMotto();
            }
            avatarUri = account.getAvatarUri();
        }
        binding.tvProfileName.setText(displayName);
        binding.tvProfileMotto.setText(motto);
        binding.tvProfileAvatar.setText(
                displayName.isEmpty() ? "U" : displayName.substring(0, 1).toUpperCase(Locale.ROOT));

        if (avatarUri != null && !avatarUri.isEmpty()) {
            binding.ivProfileAvatar.setVisibility(View.VISIBLE);
            if (AvatarPresets.isPreset(avatarUri)) {
                binding.ivProfileAvatar.setImageResource(AvatarPresets.drawableFor(avatarUri));
            } else {
                ImageLoader.load(binding.ivProfileAvatar, avatarUri, 240);
            }
            binding.tvProfileAvatar.setVisibility(View.GONE);
        } else {
            binding.ivProfileAvatar.setVisibility(View.GONE);
            binding.ivProfileAvatar.setImageDrawable(null);
            binding.tvProfileAvatar.setVisibility(View.VISIBLE);
        }

        int totalCheckIns = HabitUtils.totalCheckIns(habits);
        int bestStreak = 0;
        int completedToday = 0;
        int reminderCount = 0;
        HabitItem bestHabit = null;
        for (HabitItem item : habits) {
            if (item.getCompletedDates().contains(today)) {
                completedToday++;
            }
            if (item.isReminderEnabled()) {
                reminderCount++;
            }
            int streak = HabitUtils.currentStreak(item.getCompletedDates());
            if (streak > bestStreak) {
                bestStreak = streak;
                bestHabit = item;
            }
        }
        tvProfileHabitCount.setText(String.valueOf(habits.size()));
        tvProfileCheckInCount.setText(String.valueOf(totalCheckIns));
        tvProfileTodayCount.setText(String.valueOf(completedToday));
        tvProfileBestStreak.setText(getString(R.string.stat_streak_days_nospace, bestStreak));

        binding.layoutProfileInfo.removeAllViews();
        addStatRow(binding.layoutProfileInfo, getString(R.string.profile_current_user_label), displayName);
        addStatRow(binding.layoutProfileInfo, getString(R.string.profile_today_date_label), today);
        addStatRow(binding.layoutProfileInfo, getString(R.string.profile_reminder_enabled_label),
                getString(R.string.count_items_suffix, reminderCount));

        if (bestHabit == null) {
            binding.cardBestHabit.setVisibility(View.GONE);
        } else {
            binding.cardBestHabit.setVisibility(View.VISIBLE);
            binding.tvBestHabitTitle.setText(bestHabit.getTitle());
            binding.tvBestHabitCategory.setText(getString(R.string.best_habit_category, bestHabit.getCategory()));
            binding.tvBestHabitStreak.setText(getString(R.string.best_habit_streak,
                    HabitUtils.currentStreak(bestHabit.getCompletedDates())));
        }

        binding.layoutProfileCategories.removeAllViews();
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
                addStatRow(binding.layoutProfileCategories, category, getString(R.string.count_items_suffix, count));
            }
        }

        updateBadgePreview();
    }

    private void updateBadgePreview() {
        List<Badge> badges = BadgeUtils.evaluate(habits);
        int unlocked = BadgeUtils.unlockedCount(badges);
        binding.tvBadgeProgress.setText(unlocked + " / " + badges.size());

        binding.layoutBadgePreview.removeAllViews();
        List<Badge> unlockedBadges = new ArrayList<>();
        for (Badge badge : badges) {
            if (badge.isUnlocked()) {
                unlockedBadges.add(badge);
            }
        }

        if (unlockedBadges.isEmpty()) {
            binding.tvBadgeEmpty.setVisibility(View.VISIBLE);
            binding.scrollBadgePreview.setVisibility(View.GONE);
            return;
        }
        binding.tvBadgeEmpty.setVisibility(View.GONE);
        binding.scrollBadgePreview.setVisibility(View.VISIBLE);

        for (Badge badge : unlockedBadges) {
            ItemBadgeBinding badgeBinding =
                    ItemBadgeBinding.inflate(getLayoutInflater(), binding.layoutBadgePreview, false);
            badgeBinding.tvBadgeIcon.setText(badge.getEmoji());
            badgeBinding.tvBadgeName.setText(badge.getTitle());
            badgeBinding.tvBadgeIcon.setBackgroundResource(R.drawable.bg_badge_unlocked);
            badgeBinding.getRoot().setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), BadgeWallActivity.class)));
            binding.layoutBadgePreview.addView(badgeBinding.getRoot());
        }
    }

    private void confirmDeleteAccount() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_delete_account_title)
                .setMessage(R.string.dialog_delete_account_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    // 删除涉及写库、取消提醒、删图片，放后台线程，完成后回主线程跳登录页
                    AppExecutors.getInstance().diskIO().execute(() -> {
                        repository.deleteCurrentAccountAndData();
                        StreakWidgetRefresh();
                        AppExecutors.getInstance().mainThread().execute(() -> {
                            if (!isAdded()) {
                                return;
                            }
                            android.widget.Toast.makeText(requireContext(),
                                    R.string.toast_account_deleted, android.widget.Toast.LENGTH_SHORT).show();
                            if (getActivity() instanceof DashboardHost) {
                                ((DashboardHost) getActivity()).onLoggedOut();
                            }
                        });
                    });
                })
                .show();
    }

    /** 刷新桌面小组件（删号后清了本账号习惯，需同步小组件避免显示已删账号的旧进度）。 */
    private void StreakWidgetRefresh() {
        com.streak.app.widget.StreakWidgetProvider.refreshAll(requireContext().getApplicationContext());
    }

    private void showThemeModeChooser() {
        int current = repository.getThemeMode();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_dark_mode_title)
                .setSingleChoiceItems(getResources().getStringArray(R.array.theme_options), current, (dialog, which) -> {
                    dialog.dismiss();
                    if (which != repository.getThemeMode()) {
                        repository.setThemeMode(which);
                        updateThemeModeButtonText();
                        StreakApp.applyTheme(which);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void updateThemeModeButtonText() {
        int mode = repository.getThemeMode();
        String[] themeLabels = getResources().getStringArray(R.array.theme_options);
        String label = mode >= 0 && mode < themeLabels.length ? themeLabels[mode] : themeLabels[0];
        binding.btnThemeMode.setText(getString(R.string.btn_theme_mode_label, label));
    }

    private void shareAchievementCard() {
        if (habits.isEmpty()) {
            android.widget.Toast.makeText(requireContext(),
                    R.string.toast_no_habit_create_first, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(requireContext(), ShareReportActivity.class)
                .putExtra(ShareReportActivity.EXTRA_DISPLAY_NAME, displayName));
    }

    private void addStatRow(ViewGroup container, String label, String value) {
        ItemStatRowBinding rowBinding = ItemStatRowBinding.inflate(getLayoutInflater(), container, false);
        rowBinding.tvStatLabel.setText(label);
        rowBinding.tvStatValue.setText(value);
        container.addView(rowBinding.getRoot());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
