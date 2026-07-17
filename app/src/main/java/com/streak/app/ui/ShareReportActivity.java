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

import com.streak.app.R;
import com.streak.app.databinding.ActivityShareReportBinding;
import com.streak.app.model.HabitItem;
import com.streak.app.storage.AppRepository;
import com.streak.app.util.AppExecutors;
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

    // 统一走应用级线程池（取代自建 executor/handler）：diskIO 单线程串行，与原 single-thread
    // executor 语义一致（读盘 + 位图渲染顺序执行）；ui 回主线程。共享池与进程同寿，不在此关闭。
    private final java.util.concurrent.Executor bg = AppExecutors.getInstance().diskIO();
    private final java.util.concurrent.Executor ui = AppExecutors.getInstance().mainThread();

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
                        toast(getString(R.string.toast_need_storage_permission_save));
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
                    toast(getString(R.string.toast_report_generate_failed));
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
        toast(getString(R.string.toast_saving_to_album));
        bg.execute(() -> {
            Uri saved = repository.saveQrToGallery(card, "streak_report");
            post(() -> toast(getString(saved != null ? R.string.toast_saved_to_album : R.string.toast_save_failed_retry)));
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
                    toast(getString(R.string.toast_share_failed_retry));
                    return;
                }
                android.content.Intent share = new android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("image/png")
                        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(android.content.Intent.createChooser(share, getString(R.string.share_chooser_report_title)));
            });
        });
    }

    private void post(Runnable r) {
        ui.execute(() -> {
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
