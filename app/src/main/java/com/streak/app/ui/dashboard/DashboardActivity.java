package com.streak.app.ui.dashboard;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.streak.app.R;
import com.streak.app.databinding.ActivityDashboardBinding;
import com.streak.app.ui.MainActivity;
import com.streak.app.util.AppExecutors;
import com.streak.app.widget.StreakWidgetProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.concurrent.Executor;

/**
 * Dashboard 宿主（Phase C 收官）。把四个 Fragment 用底部导航装起来，取代旧
 * {@code MainActivity} 里内联的 1700+ 行仪表盘逻辑——那些逻辑已分别在各 Fragment 里重实现。
 *
 * <p><b>薄宿主职责。</b>只负责：① 承载并按底部导航切换四个 Fragment（show/hide 保状态，
 * 不重建）；② toolbar 菜单（导出/导入/退出）；③ 两个 FAB（新建/扫码，转发给 HabitsFragment）；
 * ④ 持有导入导出的 SAF launcher；⑤ 实现 {@link DashboardHost}，删号/退出后跳回登录页。
 * 各页数据由共享 {@link DashboardViewModel} 提供，Fragment 各自 observe 自己关心的部分。</p>
 *
 * <p><b>为什么用 show/hide 而非 replace。</b>四页切换频繁，replace 每次销毁重建 View 会丢失
 * 滚动位置/展开态且徒增开销；show/hide 让四个 Fragment 实例常驻，切换只改可见性，
 * 与旧 MainActivity 的多视图显隐语义一致。</p>
 */
public class DashboardActivity extends AppCompatActivity implements DashboardHost {

    private ActivityDashboardBinding binding;
    private DashboardViewModel viewModel;

    private HabitsFragment habitsFragment;
    private CalendarFragment calendarFragment;
    private StatsFragment statsFragment;
    private ProfileFragment profileFragment;
    private Fragment activeFragment;

    private final Executor backgroundExecutor = AppExecutors.getInstance().diskIO();
    private final Executor mainThread = AppExecutors.getInstance().mainThread();

    // 导出：先由仓库打包成临时 zip，再用 SAF 让用户选择保存位置后复制过去。
    private ActivityResultLauncher<String> exportLauncher;
    private ActivityResultLauncher<String[]> importLauncher;
    private File pendingExportFile;

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
        binding = ActivityDashboardBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 系统栏 insets：toolbar 顶部 + bottomNav 底部绝对赋值（基准值 + 系统栏），避免累加。
        final int toolbarBaseTop = binding.toolbarDashboard.getPaddingTop();
        final int bottomNavBaseBottom = binding.bottomNavigation.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            binding.toolbarDashboard.setPadding(
                    binding.toolbarDashboard.getPaddingLeft(),
                    toolbarBaseTop + top,
                    binding.toolbarDashboard.getPaddingRight(),
                    binding.toolbarDashboard.getPaddingBottom());
            binding.bottomNavigation.setPadding(
                    binding.bottomNavigation.getPaddingLeft(),
                    binding.bottomNavigation.getPaddingTop(),
                    binding.bottomNavigation.getPaddingRight(),
                    bottomNavBaseBottom + bottom);
            return insets;
        });

        viewModel = new ViewModelProvider(this).get(DashboardViewModel.class);

        setupLaunchers();
        setupFragments(savedInstanceState);
        setupToolbarAndNav();

        // 首次进入加载数据；旋转重建时 ViewModel 已存活，无需重复加载（但 reload 幂等，代价小）。
        viewModel.reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 跨午夜回前台：让当前可见页按需自刷新（各 Fragment 在 onPageShown 里处理 today 变更）。
        if (activeFragment instanceof HabitsFragment) {
            ((HabitsFragment) activeFragment).onPageShown();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pendingExportFile != null) {
            outState.putString("pending_export_file", pendingExportFile.getAbsolutePath());
        }
    }

    private void setupFragments(Bundle savedInstanceState) {
        FragmentManager fm = getSupportFragmentManager();
        if (savedInstanceState != null) {
            // 重建：复用已存在的 Fragment 实例，避免叠加多份。
            habitsFragment = (HabitsFragment) fm.findFragmentByTag("habits");
            calendarFragment = (CalendarFragment) fm.findFragmentByTag("calendar");
            statsFragment = (StatsFragment) fm.findFragmentByTag("stats");
            profileFragment = (ProfileFragment) fm.findFragmentByTag("profile");
        }
        if (habitsFragment == null) {
            habitsFragment = new HabitsFragment();
            calendarFragment = new CalendarFragment();
            statsFragment = new StatsFragment();
            profileFragment = new ProfileFragment();
            fm.beginTransaction()
                    .add(R.id.dashboardFragmentContainer, profileFragment, "profile").hide(profileFragment)
                    .add(R.id.dashboardFragmentContainer, statsFragment, "stats").hide(statsFragment)
                    .add(R.id.dashboardFragmentContainer, calendarFragment, "calendar").hide(calendarFragment)
                    .add(R.id.dashboardFragmentContainer, habitsFragment, "habits")
                    .commit();
            activeFragment = habitsFragment;
        } else {
            // 重建后当前显示的那个是未被 hide 的；默认回到习惯页。
            activeFragment = habitsFragment;
            fm.beginTransaction()
                    .hide(calendarFragment).hide(statsFragment).hide(profileFragment)
                    .show(habitsFragment)
                    .commit();
        }
    }

    private void setupToolbarAndNav() {
        binding.toolbarDashboard.setTitle(getString(R.string.title_habits));
        binding.toolbarDashboard.inflateMenu(R.menu.menu_dashboard);
        binding.toolbarDashboard.setOnMenuItemClickListener(this::onToolbarMenuClicked);

        binding.bottomNavigation.setOnItemSelectedListener(this::onBottomNavigationSelected);
        binding.bottomNavigation.setSelectedItemId(R.id.nav_habits);

        binding.fabAddHabit.setOnClickListener(v -> habitsFragment.showTemplateChooser());
        binding.fabScanHabit.setOnClickListener(v -> habitsFragment.launchCameraScan());
    }

    private boolean onBottomNavigationSelected(@NonNull android.view.MenuItem item) {
        int id = item.getItemId();
        binding.toolbarDashboard.setTitle(item.getTitle());

        Fragment target;
        boolean habitsPage = id == R.id.nav_habits;
        if (id == R.id.nav_habits) {
            target = habitsFragment;
        } else if (id == R.id.nav_calendar) {
            target = calendarFragment;
        } else if (id == R.id.nav_stats) {
            target = statsFragment;
        } else {
            target = profileFragment;
        }

        if (target != activeFragment) {
            getSupportFragmentManager().beginTransaction()
                    .hide(activeFragment).show(target).commit();
            activeFragment = target;
        }

        // 两个 FAB 仅在习惯页显示。
        binding.fabAddHabit.setVisibility(habitsPage ? View.VISIBLE : View.GONE);
        binding.fabScanHabit.setVisibility(habitsPage ? View.VISIBLE : View.GONE);

        // 切页副作用：委托各 Fragment 的 public 方法（与旧 MainActivity 一致）。
        if (id == R.id.nav_habits) {
            habitsFragment.onPageShown();
        } else if (id == R.id.nav_calendar) {
            calendarFragment.resetToCurrentMonth();
        } else if (id == R.id.nav_stats) {
            statsFragment.replayPie();
        }
        return true;
    }

    private boolean onToolbarMenuClicked(@NonNull android.view.MenuItem item) {
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
            onLoggedOut();
            return true;
        }
        return false;
    }

    private void setupLaunchers() {
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
                        Toast.makeText(this, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    backgroundExecutor.execute(() -> {
                        boolean success = copyFileToUri(exportFile, uri);
                        //noinspection ResultOfMethodCallIgnored
                        exportFile.delete();
                        postToUi(() -> Toast.makeText(
                                this,
                                success ? getString(R.string.toast_export_success)
                                        : getString(R.string.toast_save_failed_retry),
                                Toast.LENGTH_SHORT).show());
                    });
                });

        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null) {
                        return;
                    }
                    Toast.makeText(this, R.string.toast_importing, Toast.LENGTH_SHORT).show();
                    backgroundExecutor.execute(() -> {
                        boolean success = viewModel.repository().importBackup(uri);
                        if (success) {
                            StreakWidgetProvider.refreshAll(getApplicationContext());
                        }
                        postToUi(() -> {
                            Toast.makeText(
                                    this,
                                    success ? getString(R.string.toast_import_success)
                                            : getString(R.string.toast_import_failed),
                                    Toast.LENGTH_SHORT).show();
                            if (success) {
                                viewModel.reload();
                            }
                        });
                    });
                });
    }

    private void exportBackup() {
        Toast.makeText(this, R.string.toast_packing_backup, Toast.LENGTH_SHORT).show();
        backgroundExecutor.execute(() -> {
            File exportFile = viewModel.repository().exportBackup();
            postToUi(() -> {
                if (exportFile == null) {
                    Toast.makeText(this, R.string.toast_backup_pack_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                pendingExportFile = exportFile;
                exportLauncher.launch(exportFile.getName());
            });
        });
    }

    private void confirmImport() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_import_title)
                .setMessage(R.string.dialog_import_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.dialog_import_positive, (dialog, which) ->
                        importLauncher.launch(new String[]{"application/zip", "application/octet-stream"}))
                .show();
    }

    private boolean copyFileToUri(File file, android.net.Uri uri) {
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

    private void postToUi(Runnable action) {
        mainThread.execute(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            action.run();
        });
    }

    // ---- DashboardHost ----

    @Override
    public void onAccountDeleted() {
        // 删号：数据已由 ProfileFragment 清理，这里只负责跳回登录页。
        goToLogin();
    }

    @Override
    public void onLoggedOut() {
        // 退出：取消本账号提醒 + 清登录态（后台 IO），随即刷新组件，再回登录页。
        backgroundExecutor.execute(() -> {
            viewModel.repository().logout();
            StreakWidgetProvider.refreshAll(getApplicationContext());
        });
        goToLogin();
    }

    private void goToLogin() {
        Intent intent = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
