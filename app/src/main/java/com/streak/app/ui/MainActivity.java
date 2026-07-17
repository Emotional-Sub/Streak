package com.streak.app.ui;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.streak.app.R;
import com.streak.app.databinding.ActivityMainBinding;
import com.streak.app.storage.AppRepository;
import com.streak.app.ui.dashboard.DashboardActivity;
import com.streak.app.util.AppExecutors;
import com.streak.app.widget.StreakWidgetProvider;

import java.util.concurrent.Executor;

/**
 * 登录页（Phase C 收官后只负责登录）。
 *
 * <p><b>职责收敛。</b>历史上本类是「单 Activity 多页」的巨型宿主（1700+ 行），既管登录又内联
 * 承载习惯/日历/统计/我的四页。Phase C 把四页拆成独立 Fragment、由 {@link DashboardActivity}
 * 宿主承载后，本类瘦身为纯登录页：校验登录、注册跳转、登录前的备份恢复、通知权限。
 * 登录成功（或已登录）后跳 {@link DashboardActivity} 并 finish 自身。</p>
 *
 * <p><b>为什么导入放在登录页。</b>备份恢复是「离线优先、无后端」App 的重装/换机迁移入口，
 * 需在未登录态可用（备份是整机全量，不依赖当前账号）。故登录页保留「导入备份」按钮。</p>
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private AppRepository repository;

    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private ActivityResultLauncher<String[]> importLauncher;
    private ActivityResultLauncher<Intent> registerLauncher;

    private final Executor backgroundExecutor = AppExecutors.getInstance().diskIO();
    private final Executor mainThread = AppExecutors.getInstance().mainThread();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 登录卡片顶部留出状态栏高度，避免被系统栏遮挡。
        final int rootBaseTop = binding.getRoot().getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(v.getPaddingLeft(), rootBaseTop + top, v.getPaddingRight(), bottom);
            return insets;
        });

        repository = new AppRepository(this);

        setupLaunchers();
        setupLoginViews();
        requestNotificationPermissionIfNeeded();
        loadLoginState();
    }

    private void setupLaunchers() {
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> { });

        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null) {
                        return;
                    }
                    Toast.makeText(this, R.string.toast_importing, Toast.LENGTH_SHORT).show();
                    backgroundExecutor.execute(() -> {
                        boolean success = repository.importBackup(uri);
                        if (success) {
                            StreakWidgetProvider.refreshAll(getApplicationContext());
                        }
                        postToUi(() -> Toast.makeText(
                                this,
                                success ? getString(R.string.toast_import_success)
                                        : getString(R.string.toast_import_failed),
                                Toast.LENGTH_SHORT).show());
                    });
                });

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
                });
    }

    private void setupLoginViews() {
        binding.btnLogin.setOnClickListener(v -> attemptLogin());
        binding.btnLoginImport.setOnClickListener(v -> confirmImport());
        binding.tvRegisterAccount.setOnClickListener(v ->
                registerLauncher.launch(new Intent(this, RegisterActivity.class)));
    }

    private void loadLoginState() {
        // 安全整改：只回填记住的用户名，不再回填密码（密码已不再持久化）。
        binding.etLoginUsername.setText(repository.getSavedUsername());
        binding.cbRememberPassword.setChecked(repository.isRememberPassword());

        // 已登录：直接进 Dashboard，不停留在登录页。
        if (!TextUtils.isEmpty(repository.getCurrentUser())) {
            goToDashboard();
        }
    }

    private void attemptLogin() {
        String username = getText(binding.etLoginUsername);
        // 密码不做 trim：注册/改密处都按原样（含首尾空格）保存并哈希，登录若 trim 会与
        // 存库口径不一致，导致「带空格的密码注册后登录不上」。用户名仍 trim（注册也 trim）。
        String password = String.valueOf(binding.etLoginPassword.getText());

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, R.string.toast_input_username_password, Toast.LENGTH_SHORT).show();
            return;
        }

        // PBKDF2 校验较重，放后台线程，避免阻塞 UI；校验期间禁用按钮防重复点击
        binding.btnLogin.setEnabled(false);
        boolean remember = binding.cbRememberPassword.isChecked();
        backgroundExecutor.execute(() -> {
            boolean ok = repository.validateLogin(username, password);
            if (ok) {
                repository.saveLoginState(username, password, remember, username);
                // 归属修复：把无主/孤儿习惯（owner 为空或账号已不存在）认领给当前登录账号，
                // 避免旧数据被永久固定 student、或删号残留数据永远失联。
                repository.claimOrphanHabits(username);
                // 登录后重排本账号提醒：退出/切换账号时闹钟已被取消，未登录重启也不会排，
                // 登录是恢复本账号提醒的统一时机。
                repository.rescheduleAllReminders();
                // 登录后刷新桌面小组件：组件读的是当前账号数据，切换账号后需同步。
                StreakWidgetProvider.refreshAll(getApplicationContext());
            }
            postToUi(() -> {
                binding.btnLogin.setEnabled(true);
                if (ok) {
                    Toast.makeText(this, R.string.toast_login_success, Toast.LENGTH_SHORT).show();
                    goToDashboard();
                } else {
                    Toast.makeText(this, R.string.toast_login_failed, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void confirmImport() {
        importLauncher.launch(new String[]{"application/zip", "application/octet-stream"});
    }

    private void goToDashboard() {
        startActivity(new Intent(this, DashboardActivity.class));
        finish();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
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

    private String getText(android.widget.EditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }
}
