package com.streak.app.ui;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.chip.Chip;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.streak.app.R;
import com.streak.app.databinding.ActivityMainBinding;
import com.streak.app.databinding.ItemSheetHabitBinding;
import com.streak.app.databinding.ItemStatRowBinding;
import com.streak.app.databinding.ItemTemplateOptionBinding;
import com.streak.app.databinding.SheetCalendarDetailBinding;
import com.streak.app.databinding.SheetHabitPreviewBinding;
import com.streak.app.databinding.SheetTemplateChooserBinding;
import com.streak.app.databinding.ViewDashboardCalendarBinding;
import com.streak.app.databinding.ViewDashboardHabitsBinding;
import com.streak.app.databinding.ViewDashboardProfileBinding;
import com.streak.app.databinding.ViewDashboardStatsBinding;
import com.streak.app.model.CalendarCell;
import com.streak.app.model.HabitItem;
import com.streak.app.model.HabitTemplate;
import com.streak.app.model.UserAccount;
import com.streak.app.storage.AppRepository;
import com.streak.app.util.HabitUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements HabitAdapter.Callback {
    private ActivityMainBinding binding;
    private ViewDashboardHabitsBinding habitsBinding;
    private ViewDashboardCalendarBinding calendarBinding;
    private ViewDashboardStatsBinding statsBinding;
    private ViewDashboardProfileBinding profileBinding;
    private TextView tvStatsHabitCount;
    private TextView tvStatsTotalCheckIns;
    private TextView tvStatsBestStreak;
    private TextView tvStatsCompletionRate;
    private TextView tvProfileHabitCount;
    private TextView tvProfileCheckInCount;
    private TextView tvProfileTodayCount;
    private TextView tvProfileBestStreak;
    private AppRepository repository;
    private HabitAdapter habitAdapter;
    private final List<HabitItem> allHabits = new ArrayList<>();
    private String selectedCategory = "全部";
    private String currentUser = "";
    private String today = HabitUtils.today();
    private File pendingExportFile;

    private static final String[] SLOGANS = {
            "把想坚持的事放进这里，每天轻轻点一下，进度就会被认真记住。",
            "今天也是值得记录的一天，先完成，再完美。",
            "微小的坚持，会在某天给你惊喜。",
            "别小看每一次打卡，它们正在悄悄塑造你。",
            "进度不必很快，只要别停下来。",
            "种一棵树最好的时间是十年前，其次是现在。",
            "你已经比昨天的自己更靠近目标一点了。",
            "把大目标拆成今天能做到的一小步。",
            "坚持一件小事，胜过三分钟热度的雄心。",
            "每天一点点，攒起来就是了不起。"
    };

    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private ActivityResultLauncher<String> exportLauncher;
    private ActivityResultLauncher<String[]> importLauncher;
    private ActivityResultLauncher<Intent> editorLauncher;
    private ActivityResultLauncher<Intent> registerLauncher;
    private ActivityResultLauncher<Intent> profileEditLauncher;

    private final java.util.concurrent.ExecutorService backgroundExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        if (savedInstanceState != null) {
            String path = savedInstanceState.getString("pending_export_file");
            if (path != null) {
                pendingExportFile = new File(path);
            }
        }
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        habitsBinding = ViewDashboardHabitsBinding.bind(findViewById(R.id.pageHabits));
        calendarBinding = ViewDashboardCalendarBinding.bind(findViewById(R.id.pageCalendar));
        statsBinding = ViewDashboardStatsBinding.bind(findViewById(R.id.pageStats));
        profileBinding = ViewDashboardProfileBinding.bind(findViewById(R.id.pageProfile));
        tvStatsHabitCount = findViewById(R.id.tvStatsHabitCount);
        tvStatsTotalCheckIns = findViewById(R.id.tvStatsTotalCheckIns);
        tvStatsBestStreak = findViewById(R.id.tvStatsBestStreak);
        tvStatsCompletionRate = findViewById(R.id.tvStatsCompletionRate);
        tvProfileHabitCount = findViewById(R.id.tvProfileHabitCount);
        tvProfileCheckInCount = findViewById(R.id.tvProfileCheckInCount);
        tvProfileTodayCount = findViewById(R.id.tvProfileTodayCount);
        tvProfileBestStreak = findViewById(R.id.tvProfileBestStreak);

        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            binding.toolbarDashboard.setPadding(
                    binding.toolbarDashboard.getPaddingLeft(),
                    binding.toolbarDashboard.getPaddingTop() + top,
                    binding.toolbarDashboard.getPaddingRight(),
                    binding.toolbarDashboard.getPaddingBottom()
            );
            binding.bottomNavigation.setPadding(
                    binding.bottomNavigation.getPaddingLeft(),
                    binding.bottomNavigation.getPaddingTop(),
                    binding.bottomNavigation.getPaddingRight(),
                    bottom
            );
            return insets;
        });

        repository = new AppRepository(this);
        habitAdapter = new HabitAdapter(this);

        setupLaunchers();
        setupLoginViews();
        setupDashboardViews();
        requestNotificationPermissionIfNeeded();
        loadLoginState();
    }

    @Override
    protected void onDestroy() {
        backgroundExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pendingExportFile != null) {
            outState.putString("pending_export_file", pendingExportFile.getAbsolutePath());
        }
    }

    private void setupLaunchers() {
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> { }
        );

        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/zip"),
                uri -> {
                    File exportFile = pendingExportFile;
                    pendingExportFile = null;
                    if (exportFile == null) {
                        return;
                    }
                    if (uri == null) {
                        //noinspection ResultOfMethodCallIgnored
                        exportFile.delete();
                        Toast.makeText(this, "已取消导出", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    backgroundExecutor.execute(() -> {
                        boolean success = copyFileToUri(exportFile, uri);
                        //noinspection ResultOfMethodCallIgnored
                        exportFile.delete();
                        mainHandler.post(() -> Toast.makeText(
                                this,
                                success ? "备份已导出（含照片），请到你选择的位置查看" : "保存失败，请重试",
                                Toast.LENGTH_SHORT
                        ).show());
                    });
                }
        );

        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null) {
                        return;
                    }
                    Toast.makeText(this, "正在导入恢复…", Toast.LENGTH_SHORT).show();
                    backgroundExecutor.execute(() -> {
                        boolean success = repository.importBackup(uri);
                        mainHandler.post(() -> {
                            Toast.makeText(
                                    this,
                                    success ? "导入成功，数据已恢复" : "导入失败，请确认选择的是本应用导出的备份",
                                    Toast.LENGTH_SHORT
                            ).show();
                            if (success && !TextUtils.isEmpty(repository.getCurrentUser())) {
                                refreshDashboardData();
                            }
                        });
                    });
                }
        );

        editorLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        refreshDashboardData();
                    }
                }
        );

        registerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        String username = result.getData().getStringExtra(RegisterActivity.RESULT_USERNAME);
                        if (!TextUtils.isEmpty(username)) {
                            binding.etLoginUsername.setText(username);
                            binding.etLoginPassword.requestFocus();
                        }
                    }
                }
        );

        profileEditLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        refreshDashboardData();
                    }
                }
        );
    }

    private void setupLoginViews() {
        binding.btnLogin.setOnClickListener(v -> attemptLogin());
        binding.btnLoginImport.setOnClickListener(v -> confirmImport());
        binding.tvRegisterAccount.setOnClickListener(v ->
                registerLauncher.launch(new Intent(this, RegisterActivity.class)));
    }

    private void setupDashboardViews() {
        binding.toolbarDashboard.setTitle("每日打卡");
        binding.toolbarDashboard.inflateMenu(R.menu.menu_dashboard);
        binding.toolbarDashboard.setOnMenuItemClickListener(this::onToolbarMenuClicked);

        habitsBinding.rvHabits.setLayoutManager(new LinearLayoutManager(this));
        habitsBinding.rvHabits.setAdapter(habitAdapter);

        buildCategoryChips();
        habitsBinding.etSearchHabits.addTextChangedListener(simpleWatcher(this::applyHabitFilters));

        binding.bottomNavigation.setOnItemSelectedListener(this::onBottomNavigationSelected);
        binding.bottomNavigation.setSelectedItemId(R.id.nav_habits);

        binding.fabAddHabit.setOnClickListener(v -> showTemplateChooser());

        profileBinding.btnEditProfile.setOnClickListener(v ->
                profileEditLauncher.launch(new Intent(this, ProfileEditActivity.class)));
        profileBinding.btnDeleteAccount.setOnClickListener(v -> confirmDeleteAccount());
    }

    private void loadLoginState() {
        binding.etLoginUsername.setText(repository.getSavedUsername());
        binding.etLoginPassword.setText(repository.getSavedPassword());
        binding.cbRememberPassword.setChecked(repository.isRememberPassword());

        currentUser = repository.getCurrentUser();
        if (TextUtils.isEmpty(currentUser)) {
            showLoginPage();
        } else {
            showDashboardPage();
            refreshDashboardData();
        }
    }

    private void attemptLogin() {
        String username = getText(binding.etLoginUsername);
        String password = getText(binding.etLoginPassword);

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "请输入用户名和密码", Toast.LENGTH_SHORT).show();
            return;
        }

        if (repository.validateLogin(username, password)) {
            repository.saveLoginState(
                    username,
                    password,
                    binding.cbRememberPassword.isChecked(),
                    username
            );
            currentUser = username;
            showDashboardPage();
            refreshDashboardData();
            Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "用户名或密码错误", Toast.LENGTH_SHORT).show();
        }
    }


    private boolean onToolbarMenuClicked(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_export) {
            exportBackup();
            return true;
        }
        if (itemId == R.id.action_import) {
            confirmImport();
            return true;
        }
        if (itemId == R.id.action_logout) {
            repository.logout();
            currentUser = "";
            showLoginPage();
            return true;
        }
        return false;
    }

    private boolean onBottomNavigationSelected(@NonNull MenuItem item) {
        habitsBinding.pageHabits.setVisibility(item.getItemId() == R.id.nav_habits ? View.VISIBLE : View.GONE);
        calendarBinding.pageCalendar.setVisibility(item.getItemId() == R.id.nav_calendar ? View.VISIBLE : View.GONE);
        statsBinding.pageStats.setVisibility(item.getItemId() == R.id.nav_stats ? View.VISIBLE : View.GONE);
        profileBinding.pageProfile.setVisibility(item.getItemId() == R.id.nav_profile ? View.VISIBLE : View.GONE);
        binding.fabAddHabit.setVisibility(item.getItemId() == R.id.nav_habits ? View.VISIBLE : View.GONE);
        if (item.getItemId() == R.id.nav_habits) {
            refreshSlogan();
        }
        if (item.getItemId() == R.id.nav_stats) {
            statsBinding.pieCategory.replay();
        }
        return true;
    }

    private void showLoginPage() {
        binding.loginContainer.setVisibility(View.VISIBLE);
        binding.dashboardRoot.setVisibility(View.GONE);
    }

    private void showDashboardPage() {
        binding.loginContainer.setVisibility(View.GONE);
        binding.dashboardRoot.setVisibility(View.VISIBLE);
    }

    private void refreshDashboardData() {
        today = HabitUtils.today();
        currentUser = repository.getCurrentUser();
        allHabits.clear();
        allHabits.addAll(repository.readHabits());
        binding.toolbarDashboard.setSubtitle("欢迎你，" + currentUser);
        applyHabitFilters();
        updateSummarySection();
        updateCalendarPage();
        updateStatsPage();
        updateProfilePage();
    }

    private void applyHabitFilters() {
        String query = getText(habitsBinding.etSearchHabits);
        List<HabitItem> filtered = HabitUtils.filterHabits(allHabits, query, selectedCategory);
        habitAdapter.submitList(filtered, today);
        habitsBinding.tvEmptyHabits.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void updateSummarySection() {
        int completedToday = 0;
        int bestStreak = 0;
        for (HabitItem item : allHabits) {
            if (item.getCompletedDates().contains(today)) {
                completedToday++;
            }
            bestStreak = Math.max(bestStreak, HabitUtils.currentStreak(item.getCompletedDates()));
        }
        habitsBinding.tvSummaryHabitCount.setText(String.valueOf(allHabits.size()));
        habitsBinding.tvSummaryTodayCount.setText(String.valueOf(completedToday));
        habitsBinding.tvSummaryBestStreak.setText(bestStreak + "天");
    }

    private void refreshSlogan() {
        habitsBinding.tvSummarySlogan.setText(SLOGANS[new java.util.Random().nextInt(SLOGANS.length)]);
    }

    private void updateCalendarPage() {
        calendarBinding.tvCalendarMonth.setText(
                LocalDate.parse(today).format(DateTimeFormatter.ofPattern("yyyy 年 MM 月", Locale.CHINA))
        );
        Set<String> completedSet = new HashSet<>();
        for (HabitItem item : allHabits) {
            completedSet.addAll(item.getCompletedDates());
        }
        List<CalendarCell> cells = HabitUtils.buildMonthCells(today, completedSet);
        calendarBinding.gridCalendar.removeAllViews();
        int cellSize = (int) (40 * getResources().getDisplayMetrics().density);
        for (CalendarCell cell : cells) {
            TextView textView = new TextView(this);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = cellSize;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            textView.setLayoutParams(params);
            textView.setGravity(android.view.Gravity.CENTER);
            textView.setText(cell.isEmpty() ? "" : String.valueOf(cell.getDay()));
            textView.setTextColor(ContextCompat.getColor(this, android.R.color.black));
            if (cell.isToday()) {
                textView.setBackgroundResource(R.drawable.bg_calendar_today);
                textView.setTextColor(ContextCompat.getColor(this, android.R.color.white));
            } else if (cell.isCompleted()) {
                textView.setBackgroundResource(R.drawable.bg_calendar_completed);
                textView.setTextColor(0xFF1A7F4B);
            } else {
                textView.setBackgroundResource(R.drawable.bg_calendar_default);
                textView.setTextColor(0xFF6B7280);
            }
            if (!cell.isEmpty()) {
                textView.setOnClickListener(v -> showCalendarDetailDialog(cell.getDate()));
            }
            calendarBinding.gridCalendar.addView(textView);
        }

        calendarBinding.layoutRankingContainer.removeAllViews();
        if (allHabits.isEmpty()) {
            calendarBinding.tvCalendarEmpty.setVisibility(View.VISIBLE);
            return;
        }
        calendarBinding.tvCalendarEmpty.setVisibility(View.GONE);
        List<HabitItem> ranking = new ArrayList<>(allHabits);
        ranking.sort((a, b) -> Integer.compare(
                HabitUtils.currentStreak(b.getCompletedDates()),
                HabitUtils.currentStreak(a.getCompletedDates())
        ));
        int limit = Math.min(5, ranking.size());
        for (int i = 0; i < limit; i++) {
            HabitItem item = ranking.get(i);
            ItemStatRowBinding rowBinding = ItemStatRowBinding.inflate(getLayoutInflater(), calendarBinding.layoutRankingContainer, false);
            rowBinding.tvStatLabel.setText((i + 1) + ". " + item.getTitle() + " · " + item.getCategory());
            rowBinding.tvStatValue.setText(HabitUtils.currentStreak(item.getCompletedDates()) + " 天");
            calendarBinding.layoutRankingContainer.addView(rowBinding.getRoot());
        }
    }

    private void updateStatsPage() {
        int totalCheckIns = HabitUtils.totalCheckIns(allHabits);
        int bestStreak = 0;
        for (HabitItem item : allHabits) {
            bestStreak = Math.max(bestStreak, HabitUtils.currentStreak(item.getCompletedDates()));
        }
        tvStatsHabitCount.setText(String.valueOf(allHabits.size()));
        tvStatsTotalCheckIns.setText(String.valueOf(totalCheckIns));
        tvStatsBestStreak.setText(bestStreak + "天");
        tvStatsCompletionRate.setText(HabitUtils.completionRate(allHabits) + "%");

        statsBinding.layoutOverviewStats.removeAllViews();
        addStatRow(statsBinding.layoutOverviewStats, "最近 7 天打卡次数", String.valueOf(HabitUtils.weeklyCheckIns(allHabits)));
        addStatRow(statsBinding.layoutOverviewStats, "本月打卡次数", String.valueOf(HabitUtils.monthlyCheckIns(allHabits)));
        int reminderCount = 0;
        for (HabitItem item : allHabits) {
            if (item.isReminderEnabled()) {
                reminderCount++;
            }
        }
        addStatRow(statsBinding.layoutOverviewStats, "开启提醒的习惯", String.valueOf(reminderCount));

        statsBinding.layoutCategoryStats.removeAllViews();
        if (allHabits.isEmpty()) {
            statsBinding.tvEmptyStats.setVisibility(View.VISIBLE);
            statsBinding.pieCategory.setVisibility(View.GONE);
            statsBinding.chipGroupPieLegend.setVisibility(View.GONE);
            return;
        }
        statsBinding.tvEmptyStats.setVisibility(View.GONE);
        updateCategoryPie();
        List<String> categories = HabitUtils.categories();
        for (String category : categories) {
            if ("全部".equals(category)) {
                continue;
            }
            int count = 0;
            for (HabitItem item : allHabits) {
                if (category.equals(item.getCategory())) {
                    count += item.getCompletedDates().size();
                }
            }
            if (count > 0) {
                addStatRow(statsBinding.layoutCategoryStats, category, String.valueOf(count));
            }
        }
    }

    private void updateCategoryPie() {
        List<CategoryPieChart.Slice> slices = new ArrayList<>();
        int totalHabits = 0;
        for (String category : HabitUtils.categories()) {
            if ("全部".equals(category)) {
                continue;
            }
            int count = 0;
            for (HabitItem item : allHabits) {
                if (category.equals(item.getCategory())) {
                    count++;
                }
            }
            if (count > 0) {
                int color = ContextCompat.getColor(this, categoryColor(category));
                slices.add(new CategoryPieChart.Slice(category, count, color));
                totalHabits += count;
            }
        }

        statsBinding.pieCategory.setVisibility(View.VISIBLE);
        statsBinding.chipGroupPieLegend.setVisibility(View.VISIBLE);
        statsBinding.pieCategory.setData(slices, String.valueOf(totalHabits));

        statsBinding.chipGroupPieLegend.removeAllViews();
        for (CategoryPieChart.Slice slice : slices) {
            int percent = totalHabits == 0 ? 0 : Math.round(slice.value * 100f / totalHabits);
            Chip chip = new Chip(this);
            chip.setText(slice.label + " " + percent + "%");
            chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(0xFFF2F3F9));
            chip.setChipStrokeWidth(0f);
            chip.setChipIconVisible(true);
            chip.setChipIcon(new android.graphics.drawable.ColorDrawable(slice.color));
            chip.setEnsureMinTouchTargetSize(false);
            chip.setClickable(false);
            statsBinding.chipGroupPieLegend.addView(chip);
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

    private void updateProfilePage() {
        UserAccount account = repository.getCurrentAccount();
        String displayName = currentUser;
        String motto = "坚持不是一次爆发，而是很多次按时完成。";
        String avatarUri = null;
        if (account != null) {
            if (!TextUtils.isEmpty(account.getDisplayName())) {
                displayName = account.getDisplayName();
            }
            if (!TextUtils.isEmpty(account.getMotto())) {
                motto = account.getMotto();
            }
            avatarUri = account.getAvatarUri();
        }
        profileBinding.tvProfileName.setText(displayName);
        profileBinding.tvProfileMotto.setText(motto);
        profileBinding.tvProfileAvatar.setText(
                displayName.isEmpty() ? "U" : displayName.substring(0, 1).toUpperCase(Locale.ROOT));

        if (!TextUtils.isEmpty(avatarUri)) {
            profileBinding.ivProfileAvatar.setVisibility(View.VISIBLE);
            profileBinding.ivProfileAvatar.setImageURI(Uri.parse(avatarUri));
            profileBinding.tvProfileAvatar.setVisibility(View.GONE);
        } else {
            profileBinding.ivProfileAvatar.setVisibility(View.GONE);
            profileBinding.ivProfileAvatar.setImageDrawable(null);
            profileBinding.tvProfileAvatar.setVisibility(View.VISIBLE);
        }

        int totalCheckIns = HabitUtils.totalCheckIns(allHabits);
        int bestStreak = 0;
        int completedToday = 0;
        int reminderCount = 0;
        HabitItem bestHabit = null;
        for (HabitItem item : allHabits) {
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
        tvProfileHabitCount.setText(String.valueOf(allHabits.size()));
        tvProfileCheckInCount.setText(String.valueOf(totalCheckIns));
        tvProfileTodayCount.setText(String.valueOf(completedToday));
        tvProfileBestStreak.setText(bestStreak + "天");

        profileBinding.layoutProfileInfo.removeAllViews();
        addStatRow(profileBinding.layoutProfileInfo, "当前用户", displayName);
        addStatRow(profileBinding.layoutProfileInfo, "今日日期", today);
        addStatRow(profileBinding.layoutProfileInfo, "提醒已开启", reminderCount + " 项");

        if (bestHabit == null) {
            profileBinding.cardBestHabit.setVisibility(View.GONE);
        } else {
            profileBinding.cardBestHabit.setVisibility(View.VISIBLE);
            profileBinding.tvBestHabitTitle.setText(bestHabit.getTitle());
            profileBinding.tvBestHabitCategory.setText("所属分类：" + bestHabit.getCategory());
            profileBinding.tvBestHabitStreak.setText("连续打卡：" + HabitUtils.currentStreak(bestHabit.getCompletedDates()) + " 天");
        }

        profileBinding.layoutProfileCategories.removeAllViews();
        for (String category : HabitUtils.categories()) {
            if ("全部".equals(category)) {
                continue;
            }
            int count = 0;
            for (HabitItem item : allHabits) {
                if (category.equals(item.getCategory())) {
                    count++;
                }
            }
            if (count > 0) {
                addStatRow(profileBinding.layoutProfileCategories, category, count + " 项");
            }
        }
    }

    private void exportBackup() {
        Toast.makeText(this, "正在打包备份…", Toast.LENGTH_SHORT).show();
        backgroundExecutor.execute(() -> {
            File exportFile = repository.exportBackup();
            mainHandler.post(() -> {
                pendingExportFile = exportFile;
                exportLauncher.launch(exportFile.getName());
            });
        });
    }

    private void confirmImport() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("导入恢复")
                .setMessage("导入会用备份中的习惯数据覆盖当前全部习惯和打卡记录，无法撤销。确定继续吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("选择备份文件", (dialog, which) ->
                        importLauncher.launch(new String[]{"application/zip", "application/octet-stream"}))
                .show();
    }

    private void confirmDeleteAccount() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除账号")
                .setMessage("确定要删除账号吗？所有习惯、打卡记录和照片将被永久清除，无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    repository.deleteCurrentAccountAndData();
                    Toast.makeText(this, "账号已删除", Toast.LENGTH_SHORT).show();
                    showLoginPage();
                })
                .show();
    }

    private void openEditor(long habitId) {
        Intent intent = new Intent(this, HabitEditorActivity.class);
        if (habitId > 0) {
            intent.putExtra(HabitEditorActivity.EXTRA_HABIT_ID, habitId);
        }
        editorLauncher.launch(intent);
    }

    private void showTemplateChooser() {
        SheetTemplateChooserBinding sheetBinding = SheetTemplateChooserBinding.inflate(getLayoutInflater());
        ViewGroup container = sheetBinding.layoutTemplateContent;
        BottomSheetDialog dialog = new BottomSheetDialog(this);

        // 空白新建
        addTemplateRow(container, "空白新建", "从零开始，自定义全部内容。", () -> {
            dialog.dismiss();
            openEditor(-1L);
        });

        // 预置模板
        for (HabitTemplate template : HabitTemplate.presets()) {
            String desc = template.getCategory() + " · 提醒 " + template.getReminderTime();
            addTemplateRow(container, template.getTitle(), desc, () -> {
                dialog.dismiss();
                openEditorWithTemplate(template);
            });
        }

        dialog.setContentView(sheetBinding.getRoot());
        dialog.show();
    }

    private void addTemplateRow(ViewGroup container, String title, String desc, Runnable onClick) {
        ItemTemplateOptionBinding rowBinding =
                ItemTemplateOptionBinding.inflate(getLayoutInflater(), container, false);
        rowBinding.tvTemplateTitle.setText(title);
        rowBinding.tvTemplateDesc.setText(desc);
        rowBinding.getRoot().setOnClickListener(v -> onClick.run());
        container.addView(rowBinding.getRoot());
    }

    private void openEditorWithTemplate(HabitTemplate template) {
        Intent intent = new Intent(this, HabitEditorActivity.class)
                .putExtra(HabitEditorActivity.EXTRA_TPL_TITLE, template.getTitle())
                .putExtra(HabitEditorActivity.EXTRA_TPL_CONTENT, template.getContent())
                .putExtra(HabitEditorActivity.EXTRA_TPL_CATEGORY, template.getCategory())
                .putExtra(HabitEditorActivity.EXTRA_TPL_REMINDER, template.getReminderTime())
                .putExtra(HabitEditorActivity.EXTRA_TPL_TAGS, template.tagsText());
        editorLauncher.launch(intent);
    }

    private void buildCategoryChips() {
        habitsBinding.chipGroupCategories.removeAllViews();
        for (String category : HabitUtils.categories()) {
            Chip chip = new Chip(this);
            chip.setText(category);
            chip.setCheckable(true);
            chip.setTag(category);
            chip.setEnsureMinTouchTargetSize(false);
            chip.setOnClickListener(v -> {
                selectedCategory = String.valueOf(chip.getTag());
                applyHabitFilters();
            });
            habitsBinding.chipGroupCategories.addView(chip);
            if ("全部".equals(category)) {
                chip.setChecked(true);
            }
        }
    }

    private void showCalendarDetailDialog(String date) {
        SheetCalendarDetailBinding sheetBinding = SheetCalendarDetailBinding.inflate(getLayoutInflater());
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(sheetBinding.getRoot());
        populateCalendarSheet(sheetBinding, date);
        dialog.show();
    }

    private void populateCalendarSheet(SheetCalendarDetailBinding sheetBinding, String date) {
        boolean isPast = date.compareTo(HabitUtils.today()) < 0;

        List<HabitItem> completed = new ArrayList<>();
        List<HabitItem> pending = new ArrayList<>();
        for (HabitItem item : allHabits) {
            if (item.getCompletedDates().contains(date)) {
                completed.add(item);
            } else {
                pending.add(item);
            }
        }

        sheetBinding.tvSheetDate.setText(date + " 打卡详情");
        sheetBinding.tvSheetSummary.setText(
                "已完成 " + completed.size() + " 项，未完成 " + pending.size() + " 项"
        );

        ViewGroup container = sheetBinding.layoutSheetContent;
        container.removeAllViews();

        if (completed.isEmpty() && pending.isEmpty()) {
            addSheetSectionTitle(container, "当天还没有任何习惯记录。");
        } else {
            if (!pending.isEmpty()) {
                addSheetSectionTitle(container, "未完成习惯");
                for (HabitItem item : pending) {
                    addSheetHabitRow(container, sheetBinding, item, date, false, isPast);
                }
            }
            if (!completed.isEmpty()) {
                addSheetSectionTitle(container, "已完成习惯");
                for (HabitItem item : completed) {
                    addSheetHabitRow(container, sheetBinding, item, date, true, isPast);
                }
            }
        }
    }

    private void addSheetSectionTitle(ViewGroup container, String title) {
        TextView textView = new TextView(this);
        textView.setText(title);
        textView.setTextColor(ContextCompat.getColor(this, R.color.streak_muted));
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
        rowBinding.tvSheetHabitStatus.setText(completed ? "已完成" : "未完成");
        rowBinding.tvSheetHabitStatus.setTextColor(
                ContextCompat.getColor(this, completed ? R.color.streak_accent : R.color.streak_muted)
        );

        if (isPast) {
            rowBinding.btnSheetToggle.setVisibility(View.VISIBLE);
            rowBinding.btnSheetToggle.setText(completed ? "撤销" : "补卡");
            rowBinding.btnSheetToggle.setOnClickListener(v -> {
                // 原地更新数据并重建弹窗内容，支持连续补卡，不关闭弹窗。
                toggleDateCheckIn(item.getId(), date, !completed);
                populateCalendarSheet(sheetBinding, date);
            });
        }

        container.addView(rowBinding.getRoot());
    }

    private void toggleDateCheckIn(long habitId, String date, boolean add) {
        List<HabitItem> habits = repository.readHabits();
        for (HabitItem target : habits) {
            if (target.getId() == habitId) {
                List<String> dates = new ArrayList<>(target.getCompletedDates());
                if (add) {
                    if (!dates.contains(date)) dates.add(date);
                } else {
                    dates.remove(date);
                }
                target.setCompletedDates(dates);
                break;
            }
        }
        repository.writeHabits(habits);
        // 同步内存数据 + 刷新背后页面，但不关闭弹窗。
        allHabits.clear();
        allHabits.addAll(repository.readHabits());
        applyHabitFilters();
        updateSummarySection();
        updateCalendarPage();
        updateStatsPage();
        updateProfilePage();
    }

    private void addStatRow(ViewGroup container, String label, String value) {
        ItemStatRowBinding rowBinding = ItemStatRowBinding.inflate(getLayoutInflater(), container, false);
        rowBinding.tvStatLabel.setText(label);
        rowBinding.tvStatValue.setText(value);
        container.addView(rowBinding.getRoot());
    }

    private String getText(TextView textView) {
        return String.valueOf(textView.getText()).trim();
    }

    private TextWatcher simpleWatcher(Runnable callback) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                callback.run();
            }
        };
    }

    private boolean copyFileToUri(File file, Uri uri) {
        try (FileInputStream inputStream = new FileInputStream(file);
             OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
            if (outputStream == null) {
                return false;
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    @Override
    public void onToggleComplete(HabitItem item) {
        List<HabitItem> habits = repository.readHabits();
        for (HabitItem target : habits) {
            if (target.getId() == item.getId()) {
                List<String> completedDates = new ArrayList<>(target.getCompletedDates());
                if (completedDates.contains(today)) {
                    completedDates.remove(today);
                } else {
                    completedDates.add(today);
                }
                target.setCompletedDates(completedDates);
                break;
            }
        }
        repository.writeHabits(habits);
        refreshDashboardData();
    }

    @Override
    public void onEdit(HabitItem item) {
        showHabitPreview(item);
    }

    private void showHabitPreview(HabitItem item) {
        SheetHabitPreviewBinding sheetBinding = SheetHabitPreviewBinding.inflate(getLayoutInflater());
        sheetBinding.tvPreviewTitle.setText(item.getTitle());
        sheetBinding.tvPreviewMeta.setText(
                item.getCategory() + " · 提醒 " + item.getReminderTime()
                        + " · 连续 " + HabitUtils.currentStreak(item.getCompletedDates()) + " 天"
        );
        sheetBinding.tvPreviewContent.setText(item.getContent());

        if (item.getTags() != null && !item.getTags().isEmpty()) {
            StringBuilder tags = new StringBuilder();
            for (String tag : item.getTags()) {
                tags.append("#").append(tag).append(" ");
            }
            sheetBinding.tvPreviewTags.setText(tags.toString().trim());
            sheetBinding.tvPreviewTags.setVisibility(View.VISIBLE);
        } else {
            sheetBinding.tvPreviewTags.setVisibility(View.GONE);
        }

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        sheetBinding.btnPreviewEdit.setOnClickListener(v -> {
            dialog.dismiss();
            openEditor(item.getId());
        });
        dialog.setContentView(sheetBinding.getRoot());
        dialog.show();
    }

    @Override
    public void onDelete(HabitItem item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除习惯")
                .setMessage("确定删除“" + item.getTitle() + "”吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    List<HabitItem> habits = repository.readHabits();
                    List<HabitItem> updated = new ArrayList<>();
                    for (HabitItem target : habits) {
                        if (target.getId() != item.getId()) {
                            updated.add(target);
                        }
                    }
                    repository.writeHabits(updated);
                    repository.cancelReminder(item.getId());
                    repository.deletePhoto(item.getImageUri());
                    refreshDashboardData();
                })
                .show();
    }
}
