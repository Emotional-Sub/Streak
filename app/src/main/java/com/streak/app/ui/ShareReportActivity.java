package com.streak.app.ui;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.streak.app.databinding.ActivityShareReportBinding;
import com.streak.app.model.HabitItem;
import com.streak.app.storage.AppRepository;
import com.streak.app.util.BadgeUtils;
import com.streak.app.util.HabitUtils;
import com.streak.app.util.ShareCardGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * 战报全屏预览页：顶部切换维度（总成就 / 近 7 天 / 近 30 天），实时预览卡片，
 * 底部保存到相册或分享到社交平台。数据自读，维度切换本地重算重绘。
 */
public class ShareReportActivity extends AppCompatActivity {

    public static final String EXTRA_DISPLAY_NAME = "extra_display_name";

    private ActivityShareReportBinding binding;
    private AppRepository repository;

    private final List<HabitItem> habits = new ArrayList<>();
    private String displayName = "我";
    private int badgeCount = 0;
    private ShareCardGenerator.Scope currentScope = ShareCardGenerator.Scope.TOTAL;
    private Bitmap currentBitmap; // 当前预览用的卡片，保存/分享复用

    private final java.util.concurrent.ExecutorService bg =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler ui =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private ActivityResultLauncher<String> storagePermissionLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityShareReportBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = new AppRepository(this);
        String name = getIntent().getStringExtra(EXTRA_DISPLAY_NAME);
        if (name != null && !name.trim().isEmpty()) {
            displayName = name.trim();
        }

        binding.toolbarShareReport.setNavigationOnClickListener(v -> finish());

        storagePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        doSaveToGallery();
                    } else {
                        toast("需要存储权限才能保存到相册");
                    }
                });

        binding.chipGroupScope.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                return;
            }
            int id = checkedIds.get(0);
            if (id == binding.chipScope7.getId()) {
                currentScope = ShareCardGenerator.Scope.LAST_7;
            } else if (id == binding.chipScope30.getId()) {
                currentScope = ShareCardGenerator.Scope.LAST_30;
            } else {
                currentScope = ShareCardGenerator.Scope.TOTAL;
            }
            renderPreview();
        });

        binding.btnReportSave.setOnClickListener(v -> requestSaveToGallery());
        binding.btnReportShare.setOnClickListener(v -> shareCurrent());

        loadDataThenRender();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        bg.shutdownNow();
        super.onDestroy();
    }

    /** 后台读一次习惯数据 + 算勋章数，随后渲染首个维度。 */
    private void loadDataThenRender() {
        bg.execute(() -> {
            final List<HabitItem> loaded = repository.readHabits();
            final int badges = BadgeUtils.unlockedCount(BadgeUtils.evaluate(loaded));
            post(() -> {
                habits.clear();
                habits.addAll(loaded);
                badgeCount = badges;
                renderPreview();
            });
        });
    }

    /** 按当前维度重新生成卡片并显示。 */
    private void renderPreview() {
        final ShareCardGenerator.Scope scope = currentScope;
        final String date = HabitUtils.today();
        final List<HabitItem> snapshot = new ArrayList<>(habits);
        final int badges = badgeCount;
        bg.execute(() -> {
            ShareCardGenerator.ReportData data =
                    ShareCardGenerator.buildData(scope, displayName, snapshot, badges, date);
            final Bitmap card = ShareCardGenerator.generate(data);
            post(() -> {
                if (card == null) {
                    toast("战报生成失败");
                    return;
                }
                currentBitmap = card;
                binding.ivReportPreview.setImageBitmap(card);
            });
        });
    }

    // ---- 保存 / 分享 ----

    private void requestSaveToGallery() {
        if (currentBitmap == null) {
            return;
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q
                && androidx.core.content.ContextCompat.checkSelfPermission(this,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        doSaveToGallery();
    }

    private void doSaveToGallery() {
        final Bitmap card = currentBitmap;
        if (card == null) {
            return;
        }
        toast("正在保存到相册…");
        bg.execute(() -> {
            Uri saved = repository.saveQrToGallery(card, "streak_report");
            post(() -> toast(saved != null ? "已保存到相册的 Streak 相册" : "保存失败，请重试"));
        });
    }

    private void shareCurrent() {
        final Bitmap card = currentBitmap;
        if (card == null) {
            return;
        }
        bg.execute(() -> {
            Uri uri = repository.cacheBitmapForShare(card, "streak_report");
            post(() -> {
                if (uri == null) {
                    toast("分享失败，请重试");
                    return;
                }
                android.content.Intent share = new android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("image/png")
                        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(android.content.Intent.createChooser(share, "分享我的坚持战报"));
            });
        });
    }

    private void post(Runnable r) {
        ui.post(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            r.run();
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
