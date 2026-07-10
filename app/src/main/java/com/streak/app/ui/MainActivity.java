package com.streak.app.ui;

import android.Manifest;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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
import com.streak.app.databinding.ItemBadgeBinding;
import com.streak.app.databinding.ItemSheetHabitBinding;
import com.streak.app.databinding.ItemStatRowBinding;
import com.streak.app.databinding.ItemTemplateOptionBinding;
import com.streak.app.databinding.SheetCalendarDetailBinding;
import com.streak.app.databinding.SheetHabitQrBinding;
import com.streak.app.databinding.SheetTemplateChooserBinding;
import com.streak.app.databinding.ViewDashboardCalendarBinding;
import com.streak.app.databinding.ViewDashboardHabitsBinding;
import com.streak.app.databinding.ViewDashboardProfileBinding;
import com.streak.app.databinding.ViewDashboardStatsBinding;
import com.streak.app.model.CalendarCell;
import com.streak.app.model.Badge;
import com.streak.app.model.HabitItem;
import com.streak.app.model.HabitTemplate;
import com.streak.app.model.UserAccount;
import com.streak.app.StreakApp;
import com.streak.app.storage.AppRepository;
import com.streak.app.util.AvatarPresets;
import com.streak.app.util.BadgeUtils;
import com.streak.app.util.ShareCardGenerator;
import com.streak.app.util.HabitQrCodec;
import com.streak.app.util.HabitUtils;
import com.streak.app.util.QrGenerator;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.YearMonth;
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
    // 日历当前显示的月份锚点（该月任意一天）；null 表示显示当月
    private String displayedMonth;
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
    private ActivityResultLauncher<com.journeyapps.barcodescanner.ScanOptions> habitScanLauncher;
    private ActivityResultLauncher<String> storagePermissionLauncher;
    // 等待存储权限授权后再保存的二维码（仅 API 26-28 用得到）
    private Bitmap pendingSaveQrBitmap;
    private String pendingSaveQrTitle;

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

        // 缓存初始 padding，insets 回调里用「基准值 + 系统栏」绝对赋值，
        // 避免每次 insets 重新分发（切 Tab / 弹键盘 / 获焦）时累加导致顶部空白越撑越大。
        final int toolbarBaseTop = binding.toolbarDashboard.getPaddingTop();
        final int bottomNavBaseBottom = binding.bottomNavigation.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            binding.toolbarDashboard.setPadding(
                    binding.toolbarDashboard.getPaddingLeft(),
                    toolbarBaseTop + top,
                    binding.toolbarDashboard.getPaddingRight(),
                    binding.toolbarDashboard.getPaddingBottom()
            );
            binding.bottomNavigation.setPadding(
                    binding.bottomNavigation.getPaddingLeft(),
                    binding.bottomNavigation.getPaddingTop(),
                    binding.bottomNavigation.getPaddingRight(),
                    bottomNavBaseBottom + bottom
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
        // 清理已投递但未执行的主线程回调，避免它们在 Activity 销毁后触碰已失效的视图/上下文
        mainHandler.removeCallbacksAndMessages(null);
        backgroundExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pendingExportFile != null) {
            outState.putString("pending_export_file", pendingExportFile.getAbsolutePath());
        }
    }

    /**
     * 把结果回主线程执行，但仅当 Activity 尚存活时才跑。
     * 后台任务完成时 Activity 可能已 finishing/destroyed，直接碰 binding/Toast/launcher 会崩，
     * 这里统一加生命周期守卫。
     */
    private void postToUi(Runnable action) {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            action.run();
        });
    }

    /**
     * 安全地向后台线程池提交任务：executor 已关闭（onDestroy 后）时静默跳过，
     * 避免 RejectedExecutionException。
     */
    private void runInBackground(Runnable task) {
        if (backgroundExecutor.isShutdown()) {
            return;
        }
        try {
            backgroundExecutor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Activity 正在销毁，丢弃任务即可
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
                    runInBackground(() -> {
                        boolean success = copyFileToUri(exportFile, uri);
                        //noinspection ResultOfMethodCallIgnored
                        exportFile.delete();
                        postToUi(() -> Toast.makeText(
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
                    runInBackground(() -> {
                        boolean success = repository.importBackup(uri);
                        postToUi(() -> {
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

        habitScanLauncher = registerForActivityResult(
                new com.journeyapps.barcodescanner.ScanContract(),
                result -> {
                    if (result.getContents() == null) {
                        return; // 用户取消
                    }
                    handleScannedContent(result.getContents());
                }
        );

        // 仅 API 26-28 保存二维码到相册前需要的存储权限
        storagePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    Bitmap qr = pendingSaveQrBitmap;
                    String title = pendingSaveQrTitle;
                    pendingSaveQrBitmap = null;
                    pendingSaveQrTitle = null;
                    if (!granted) {
                        Toast.makeText(this, "需要存储权限才能保存到相册", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (qr != null) {
                        saveQrToGallery(qr, title);
                    } else {
                        // 授权对话框显示期间进程曾被系统回收，待存二维码已丢失。
                        // 明确提示重试，而非静默失败。
                        Toast.makeText(this, "已授权，请重新点击保存到相册", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    /** 统一处理扫到/解出的原始内容：相机扫码与扫码界面内的相册识别共用。 */
    private void handleScannedContent(String raw) {
        if (raw == null) {
            Toast.makeText(this, "未识别到二维码", Toast.LENGTH_SHORT).show();
            return;
        }
        HabitQrCodec.Decoded decoded = HabitQrCodec.decode(raw);
        if (decoded == null) {
            Toast.makeText(this, "这不是有效的习惯二维码", Toast.LENGTH_SHORT).show();
            return;
        }
        openEditorWithScan(decoded);
    }

    private void launchCameraScan() {
        com.journeyapps.barcodescanner.ScanOptions options =
                new com.journeyapps.barcodescanner.ScanOptions();
        options.setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE);
        options.setPrompt("对准同学分享的习惯二维码");
        options.setBeepEnabled(false);
        // 方向交给 PortraitCaptureActivity 在 manifest 中的 portrait 声明控制，
        // 这里必须设为 false，否则 CaptureManager 会按设备当前旋转自行锁定方向，
        // 与 manifest 冲突，导致预览变横屏并出现拉伸的横线。
        options.setOrientationLocked(false);
        options.setCaptureActivity(PortraitCaptureActivity.class);
        habitScanLauncher.launch(options);
    }

    private void openEditorWithScan(HabitQrCodec.Decoded decoded) {
        Intent intent = new Intent(this, HabitEditorActivity.class)
                .putExtra(HabitEditorActivity.EXTRA_TPL_TITLE, decoded.title)
                .putExtra(HabitEditorActivity.EXTRA_TPL_CONTENT, decoded.content)
                .putExtra(HabitEditorActivity.EXTRA_TPL_CATEGORY, decoded.category)
                .putExtra(HabitEditorActivity.EXTRA_TPL_REMINDER, decoded.reminderTime)
                .putExtra(HabitEditorActivity.EXTRA_TPL_TAGS, decoded.tags);
        editorLauncher.launch(intent);
    }

    private void setupLoginViews() {
        binding.btnLogin.setOnClickListener(v -> attemptLogin());
        binding.btnLoginImport.setOnClickListener(v -> confirmImport());
        binding.tvRegisterAccount.setOnClickListener(v ->
                registerLauncher.launch(new Intent(this, RegisterActivity.class)));
    }

    private void setupDashboardViews() {
        binding.toolbarDashboard.setTitle("习惯");
        binding.toolbarDashboard.inflateMenu(R.menu.menu_dashboard);
        binding.toolbarDashboard.setOnMenuItemClickListener(this::onToolbarMenuClicked);

        habitsBinding.rvHabits.setLayoutManager(new LinearLayoutManager(this));
        habitsBinding.rvHabits.setAdapter(habitAdapter);

        buildCategoryChips();
        habitsBinding.etSearchHabits.addTextChangedListener(simpleWatcher(this::applyHabitFilters));

        binding.bottomNavigation.setOnItemSelectedListener(this::onBottomNavigationSelected);
        binding.bottomNavigation.setSelectedItemId(R.id.nav_habits);

        binding.fabAddHabit.setOnClickListener(v -> showTemplateChooser());
        binding.fabScanHabit.setOnClickListener(v -> launchCameraScan());

        // 日历翻月
        calendarBinding.btnCalendarPrev.setOnClickListener(v -> shiftCalendarMonth(-1));
        calendarBinding.btnCalendarNext.setOnClickListener(v -> shiftCalendarMonth(1));
        calendarBinding.btnCalendarToday.setOnClickListener(v -> {
            displayedMonth = null; // 复位到当月
            updateCalendarPage();
        });

        profileBinding.btnEditProfile.setOnClickListener(v ->
                profileEditLauncher.launch(new Intent(this, ProfileEditActivity.class)));
        profileBinding.btnDeleteAccount.setOnClickListener(v -> confirmDeleteAccount());
        profileBinding.btnShareReport.setOnClickListener(v -> shareAchievementCard());
        profileBinding.btnThemeMode.setOnClickListener(v -> showThemeModeChooser());
        updateThemeModeButtonText();
        profileBinding.cardBadgeWall.setOnClickListener(v ->
                startActivity(new Intent(this, BadgeWallActivity.class)));
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

        // PBKDF2 校验较重，放后台线程，避免阻塞 UI；校验期间禁用按钮防重复点击
        binding.btnLogin.setEnabled(false);
        boolean remember = binding.cbRememberPassword.isChecked();
        runInBackground(() -> {
            boolean ok = repository.validateLogin(username, password);
            if (ok) {
                repository.saveLoginState(username, password, remember, username);
            }
            postToUi(() -> {
                binding.btnLogin.setEnabled(true);
                if (ok) {
                    currentUser = username;
                    showDashboardPage();
                    refreshDashboardData();
                    Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "用户名或密码错误", Toast.LENGTH_SHORT).show();
                }
            });
        });
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
        binding.toolbarDashboard.setTitle(item.getTitle());
        habitsBinding.pageHabits.setVisibility(item.getItemId() == R.id.nav_habits ? View.VISIBLE : View.GONE);
        calendarBinding.pageCalendar.setVisibility(item.getItemId() == R.id.nav_calendar ? View.VISIBLE : View.GONE);
        statsBinding.pageStats.setVisibility(item.getItemId() == R.id.nav_stats ? View.VISIBLE : View.GONE);
        profileBinding.pageProfile.setVisibility(item.getItemId() == R.id.nav_profile ? View.VISIBLE : View.GONE);
        binding.fabAddHabit.setVisibility(item.getItemId() == R.id.nav_habits ? View.VISIBLE : View.GONE);
        binding.fabScanHabit.setVisibility(item.getItemId() == R.id.nav_habits ? View.VISIBLE : View.GONE);
        if (item.getItemId() == R.id.nav_habits) {
            refreshSlogan();
        }
        if (item.getItemId() == R.id.nav_stats) {
            statsBinding.pieCategory.replay();
        }
        // 进入日历页时复位到当月，避免停留在上次翻到的历史月份
        if (item.getItemId() == R.id.nav_calendar && displayedMonth != null) {
            displayedMonth = null;
            updateCalendarPage();
        }
        return true;
    }

    private void showLoginPage() {
        // 回到登录界面时按当前持久化状态重置输入框：
        // 删除账号会清空保存的用户名/密码，这里重读才能避免旧值残留在文本框。
        binding.etLoginUsername.setText(repository.getSavedUsername());
        binding.etLoginPassword.setText(repository.getSavedPassword());
        binding.cbRememberPassword.setChecked(repository.isRememberPassword());
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
        // 读习惯/账号涉及读盘+JSON 解析，放后台线程避免阻塞 UI（真机上会掉帧）。
        // 读完回主线程再更新视图。
        runInBackground(() -> {
            final List<HabitItem> habits = repository.readHabits();
            final String displayName = resolveDisplayName();
            postToUi(() -> {
                allHabits.clear();
                allHabits.addAll(habits);
                binding.toolbarDashboard.setSubtitle("欢迎你，" + displayName);
                applyHabitFilters();
                updateSummarySection();
                updateCalendarPage();
                updateStatsPage();
                updateProfilePage();
            });
        });
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
        // 显示锚点：翻月时用 displayedMonth，否则用今天所在月
        String anchor = displayedMonth != null ? displayedMonth : today;
        calendarBinding.tvCalendarMonth.setText(
                LocalDate.parse(anchor).format(DateTimeFormatter.ofPattern("yyyy 年 MM 月", Locale.CHINA))
        );
        // 非当月时才显示「今天」快捷按钮
        boolean viewingCurrentMonth = YearMonth.from(LocalDate.parse(anchor))
                .equals(YearMonth.from(LocalDate.parse(today)));
        calendarBinding.btnCalendarToday.setVisibility(viewingCurrentMonth ? View.GONE : View.VISIBLE);

        Set<String> completedSet = new HashSet<>();
        for (HabitItem item : allHabits) {
            completedSet.addAll(item.getCompletedDates());
        }
        // 锚点决定显示哪个月，today 仅用于高亮今日
        List<CalendarCell> cells = HabitUtils.buildMonthCells(anchor, today, completedSet);
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
            textView.setTextColor(ContextCompat.getColor(this, R.color.streak_text_primary));
            if (cell.isToday()) {
                textView.setBackgroundResource(R.drawable.bg_calendar_today);
                textView.setTextColor(ContextCompat.getColor(this, R.color.streak_on_primary));
            } else if (cell.isCompleted()) {
                textView.setBackgroundResource(R.drawable.bg_calendar_completed);
                textView.setTextColor(ContextCompat.getColor(this, R.color.streak_accent));
            } else {
                textView.setBackgroundResource(R.drawable.bg_calendar_default);
                textView.setTextColor(ContextCompat.getColor(this, R.color.streak_text_faint));
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

    /** 日历翻月：delta 为 -1（上月）或 +1（下月）。 */
    private void shiftCalendarMonth(int delta) {
        String anchor = displayedMonth != null ? displayedMonth : today;
        try {
            LocalDate shifted = LocalDate.parse(anchor).plusMonths(delta).withDayOfMonth(1);
            displayedMonth = shifted.toString();
        } catch (Exception ignored) {
            displayedMonth = null;
        }
        updateCalendarPage();
    }

    private void updateStatsPage() {
        int totalCheckIns = HabitUtils.totalCheckIns(allHabits);
        int currentBest = HabitUtils.bestCurrentStreak(allHabits);
        int longestBest = HabitUtils.bestLongestStreak(allHabits);
        tvStatsHabitCount.setText(String.valueOf(allHabits.size()));
        tvStatsTotalCheckIns.setText(String.valueOf(totalCheckIns));
        tvStatsBestStreak.setText(currentBest + "天");
        tvStatsCompletionRate.setText(HabitUtils.completionRate(allHabits) + "%");

        // 热力图数据：把所有习惯的去重打卡按日期聚合成计数
        statsBinding.heatmap.setData(buildHeatmapCounts());

        statsBinding.layoutOverviewStats.removeAllViews();
        // 当前连续 vs 历史最长
        addStatRow(statsBinding.layoutOverviewStats, "当前连续 / 历史最长",
                currentBest + " 天 / " + longestBest + " 天");
        // 周环比：本周 vs 上周
        int thisWeek = HabitUtils.weeklyCheckIns(allHabits);
        int lastWeek = HabitUtils.lastWeekCheckIns(allHabits);
        addStatRow(statsBinding.layoutOverviewStats, "最近 7 天打卡次数",
                thisWeek + "  " + weekTrendText(thisWeek, lastWeek));
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
                    count += HabitUtils.uniqueCheckIns(item);
                }
            }
            if (count > 0) {
                addStatRow(statsBinding.layoutCategoryStats, category, String.valueOf(count));
            }
        }
    }

    /** 把所有习惯的去重打卡日期聚合成「日期 -> 次数」，喂给热力图。 */
    private java.util.Map<String, Integer> buildHeatmapCounts() {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (HabitItem item : allHabits) {
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
            return "(较上周 ↑" + diff + ")";
        }
        if (diff < 0) {
            return "(较上周 ↓" + (-diff) + ")";
        }
        return "(与上周持平)";
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
            chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.streak_surface_alt)));
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

    /**
     * 优先返回昵称（displayName），为空时退回用户名。
     * 顶栏问候语与「我的」页面均用此口径。
     */
    private String resolveDisplayName() {
        UserAccount account = repository.getCurrentAccount();
        if (account != null && !TextUtils.isEmpty(account.getDisplayName())) {
            return account.getDisplayName();
        }
        return currentUser;
    }

    private void updateProfilePage() {
        UserAccount account = repository.getCurrentAccount();
        String displayName = resolveDisplayName();
        String motto = "坚持不是一次爆发，而是很多次按时完成。";
        String avatarUri = null;
        if (account != null) {
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
            if (AvatarPresets.isPreset(avatarUri)) {
                profileBinding.ivProfileAvatar.setImageResource(AvatarPresets.drawableFor(avatarUri));
            } else {
                com.streak.app.util.ImageLoader.load(profileBinding.ivProfileAvatar, avatarUri, 240);
            }
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

        updateBadgePreview();
    }

    private void updateBadgePreview() {
        List<Badge> badges = BadgeUtils.evaluate(allHabits);
        int unlocked = BadgeUtils.unlockedCount(badges);
        profileBinding.tvBadgeProgress.setText(unlocked + " / " + badges.size());

        profileBinding.layoutBadgePreview.removeAllViews();
        List<Badge> unlockedBadges = new ArrayList<>();
        for (Badge badge : badges) {
            if (badge.isUnlocked()) {
                unlockedBadges.add(badge);
            }
        }

        if (unlockedBadges.isEmpty()) {
            profileBinding.tvBadgeEmpty.setVisibility(View.VISIBLE);
            profileBinding.scrollBadgePreview.setVisibility(View.GONE);
            return;
        }
        profileBinding.tvBadgeEmpty.setVisibility(View.GONE);
        profileBinding.scrollBadgePreview.setVisibility(View.VISIBLE);

        for (Badge badge : unlockedBadges) {
            ItemBadgeBinding badgeBinding =
                    ItemBadgeBinding.inflate(getLayoutInflater(), profileBinding.layoutBadgePreview, false);
            badgeBinding.tvBadgeIcon.setText(badge.getEmoji());
            badgeBinding.tvBadgeName.setText(badge.getTitle());
            badgeBinding.tvBadgeIcon.setBackgroundResource(R.drawable.bg_badge_unlocked);
            badgeBinding.getRoot().setOnClickListener(v ->
                    startActivity(new Intent(this, BadgeWallActivity.class)));
            profileBinding.layoutBadgePreview.addView(badgeBinding.getRoot());
        }
    }

    private void exportBackup() {
        Toast.makeText(this, "正在打包备份…", Toast.LENGTH_SHORT).show();
        runInBackground(() -> {
            File exportFile = repository.exportBackup();
            postToUi(() -> {
                // exportBackup 失败会返回 null（磁盘满/写盘异常），此时提示而非崩溃
                if (exportFile == null) {
                    Toast.makeText(this, "备份打包失败，请检查存储空间后重试", Toast.LENGTH_SHORT).show();
                    return;
                }
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
                    // 删号涉及读写 JSON、取消闹钟、删图片，放后台线程避免阻塞 UI
                    runInBackground(() -> {
                        repository.deleteCurrentAccountAndData();
                        postToUi(() -> {
                            Toast.makeText(this, "账号已删除", Toast.LENGTH_SHORT).show();
                            showLoginPage();
                        });
                    });
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

        // 预置模板：当习惯页选中了具体分类（非「全部」）时，只展示该分类的模板
        int shown = 0;
        for (HabitTemplate template : HabitTemplate.presets()) {
            if (!"全部".equals(selectedCategory)
                    && !selectedCategory.equals(template.getCategory())) {
                continue;
            }
            String desc = template.getCategory() + " · 提醒 " + template.getReminderTime();
            addTemplateRow(container, template.getTitle(), desc, () -> {
                dialog.dismiss();
                openEditorWithTemplate(template);
            });
            shown++;
        }
        // 该分类没有预置模板时给个提示，避免只剩「空白新建」显得像出错
        if (shown == 0) {
            addTemplateRow(container, "该分类暂无模板",
                    "点上方「空白新建」自定义，或在习惯页切到「全部」查看所有模板。", () -> {});
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

        // 已完成且当天有备注/心情：在行下方追加一行灰字展示
        if (completed) {
            String note = item.getNote(date);
            if (!note.isEmpty()) {
                TextView noteView = new TextView(this);
                noteView.setText("“" + note + "”");
                noteView.setTextColor(ContextCompat.getColor(this, R.color.streak_muted));
                noteView.setTextSize(13f);
                int start = (int) (22 * getResources().getDisplayMetrics().density);
                noteView.setPadding(start, 0, 0, (int) (6 * getResources().getDisplayMetrics().density));
                container.addView(noteView);
            }
        }
    }

    private void toggleDateCheckIn(long habitId, String date, boolean add) {
        // 先在内存里更新（快，UI 立即刷新，支持连续补卡不关闭弹窗），
        // 再把读改写盘的磁盘 IO 放后台线程，避免在主线程连做三次读写盘造成卡顿/ANR。
        for (HabitItem target : allHabits) {
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
        applyHabitFilters();
        updateSummarySection();
        updateCalendarPage();
        updateStatsPage();
        updateProfilePage();

        // 后台持久化：读盘拿到全量列表，套用同样的改动后写回，避免覆盖其它字段。
        runInBackground(() -> {
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
        });
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
        boolean doneToday = item.getCompletedDates() != null && item.getCompletedDates().contains(today);
        if (doneToday) {
            // 撤销打卡：直接移除今天并清掉当天备注
            writeTodayCheckIn(item.getId(), false, null);
        } else {
            // 打卡：弹可选备注框（可留空/跳过，不打断快速打卡）
            promptCheckInNote(item);
        }
    }

    /** 打卡时的可选备注/心情输入框：留空或「跳过」直接完成，不强制。 */
    private void promptCheckInNote(HabitItem item) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("记一句今日心情 / 备注（可留空）");
        input.setMinLines(2);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);

        new MaterialAlertDialogBuilder(this)
                .setTitle("完成打卡：" + item.getTitle())
                .setView(wrap)
                .setNegativeButton("跳过", (d, w) -> writeTodayCheckIn(item.getId(), true, null))
                .setPositiveButton("保存", (d, w) ->
                        writeTodayCheckIn(item.getId(), true, input.getText().toString()))
                .show();
    }

    /**
     * 写入/撤销今日打卡，并可附带当天备注。读写文件放后台线程（打卡高频，避免卡主线程）。
     */
    private void writeTodayCheckIn(long habitId, boolean add, String note) {
        runInBackground(() -> {
            List<HabitItem> habits = repository.readHabits();
            for (HabitItem target : habits) {
                if (target.getId() == habitId) {
                    List<String> completedDates = new ArrayList<>(target.getCompletedDates());
                    if (add) {
                        if (!completedDates.contains(today)) {
                            completedDates.add(today);
                        }
                        target.setNote(today, note);
                    } else {
                        completedDates.remove(today);
                        target.setNote(today, null); // 清除当天备注
                    }
                    target.setCompletedDates(completedDates);
                    break;
                }
            }
            repository.writeHabits(habits);
            postToUi(this::refreshDashboardData);
        });
    }

    @Override
    public void onEdit(HabitItem item) {
        // 卡片上「编辑」直接进编辑页，不再经过预览弹窗
        openEditor(item.getId());
    }

    @Override
    public void onShare(HabitItem item) {
        // 卡片上「分享」直接出二维码
        showHabitQr(item);
    }

    private void showHabitQr(HabitItem item) {
        SheetHabitQrBinding sheetBinding = SheetHabitQrBinding.inflate(getLayoutInflater());
        sheetBinding.tvQrHabitTitle.setText(item.getTitle());

        int sizePx = (int) (240 * getResources().getDisplayMetrics().density);
        Bitmap qr = QrGenerator.generate(HabitQrCodec.encode(item), sizePx);
        if (qr == null) {
            Toast.makeText(this, "二维码生成失败", Toast.LENGTH_SHORT).show();
            return;
        }
        sheetBinding.ivQrImage.setImageBitmap(qr);

        sheetBinding.btnSaveQr.setOnClickListener(v -> requestSaveQr(qr, item.getTitle()));

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(sheetBinding.getRoot());
        dialog.show();
    }

    /**
     * 保存二维码到相册。API 29+ 直接走 MediaStore 免权限；API 26-28 需先申请存储权限，
     * 拿到后再保存（结果回到 storagePermissionLauncher 的回调）。
     */
    private void requestSaveQr(Bitmap qr, String title) {
        if (qr == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingSaveQrBitmap = qr;
            pendingSaveQrTitle = title;
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        saveQrToGallery(qr, title);
    }

    /** 在后台线程把二维码写入相册，避免阻塞主线程。 */
    private void saveQrToGallery(Bitmap qr, String title) {
        Toast.makeText(this, "正在保存到相册…", Toast.LENGTH_SHORT).show();
        runInBackground(() -> {
            Uri saved = repository.saveQrToGallery(qr, title);
            postToUi(() -> Toast.makeText(
                    this,
                    saved != null ? "已保存到相册的 Streak 相册" : "保存失败，请重试",
                    Toast.LENGTH_SHORT
            ).show());
        });
    }

    private static final String[] THEME_LABELS = {"跟随系统", "浅色", "深色"};

    /** 弹出主题模式选择：跟随系统 / 浅色 / 深色。 */
    private void showThemeModeChooser() {
        int current = repository.getThemeMode();
        new MaterialAlertDialogBuilder(this)
                .setTitle("深色模式")
                .setSingleChoiceItems(THEME_LABELS, current, (dialog, which) -> {
                    dialog.dismiss();
                    if (which != repository.getThemeMode()) {
                        repository.setThemeMode(which);
                        updateThemeModeButtonText();
                        // 立即应用；夜间模式变化会自动重建当前 Activity
                        StreakApp.applyTheme(which);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateThemeModeButtonText() {
        int mode = repository.getThemeMode();
        String label = mode >= 0 && mode < THEME_LABELS.length ? THEME_LABELS[mode] : THEME_LABELS[0];
        profileBinding.btnThemeMode.setText("深色模式：" + label);
    }

    /**
     * 生成成就战报卡片并弹出「保存到相册 / 分享」选项。数据来自当前全部习惯。
     */
    /** 打开战报全屏预览页（可切维度、预览、保存/分享）。 */
    private void shareAchievementCard() {
        if (allHabits.isEmpty()) {
            Toast.makeText(this, "还没有习惯，先去创建一个吧", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, ShareReportActivity.class)
                .putExtra(ShareReportActivity.EXTRA_DISPLAY_NAME, resolveDisplayName());
        startActivity(intent);
    }

    @Override
    public void onDelete(HabitItem item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除习惯")
                .setMessage("确定删除“" + item.getTitle() + "”吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    // 读写 JSON、取消闹钟、删图片文件都放后台线程，避免阻塞主线程
                    final long habitId = item.getId();
                    final String imageUri = item.getImageUri();
                    runInBackground(() -> {
                        List<HabitItem> habits = repository.readHabits();
                        List<HabitItem> updated = new ArrayList<>();
                        for (HabitItem target : habits) {
                            if (target.getId() != habitId) {
                                updated.add(target);
                            }
                        }
                        repository.writeHabits(updated);
                        repository.cancelReminder(habitId);
                        repository.deletePhoto(imageUri);
                        postToUi(this::refreshDashboardData);
                    });
                })
                .show();
    }
}
