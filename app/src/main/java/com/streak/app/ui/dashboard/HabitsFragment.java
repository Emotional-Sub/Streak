package com.streak.app.ui.dashboard;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.streak.app.R;
import com.streak.app.databinding.ItemTemplateOptionBinding;
import com.streak.app.databinding.SheetCheckInBinding;
import com.streak.app.databinding.SheetHabitQrBinding;
import com.streak.app.databinding.ViewDashboardHabitsBinding;
import com.streak.app.model.CameraCaptureInfo;
import com.streak.app.model.CheckInRecord;
import com.streak.app.model.HabitItem;
import com.streak.app.model.HabitTemplate;
import com.streak.app.ui.HabitAdapter;
import com.streak.app.ui.HabitDetailActivity;
import com.streak.app.ui.HabitEditorActivity;
import com.streak.app.util.AppExecutors;
import com.streak.app.util.CheckInCallbackGuard;
import com.streak.app.util.HabitQrCodec;
import com.streak.app.util.HabitUtils;
import com.streak.app.util.ImageLoader;
import com.streak.app.util.QrGenerator;
import com.streak.app.widget.StreakWidgetProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executor;

/**
 * 习惯页（Phase C 从 MainActivity 拆出）——四页里交互最重的一个：习惯列表、搜索、分类筛选、
 * FAB 新建（模板选择）、扫码导入、打卡录入弹层（心情/耗时/照片/备注）、二维码分享、编辑/删除跳转。
 *
 * <p>数据观测 {@link DashboardViewModel#getHabits()}；任何写操作（打卡/撤销/删除）完成后调
 * {@link DashboardViewModel#reload()}，各页随 LiveData 自更新，取代旧 {@code refreshDashboardData()}
 * 一次重绘全部页面。习惯编辑/扫码经 launcher 返回后同样 reload。</p>
 *
 * <p>打卡弹层的相机/相册回调在弹层构建后异步触发，需把「当前弹层状态」提升为字段供回填，
 * 弹层关闭时清理——沿用旧 MainActivity 的做法，仅把宿主从 Activity 换成 Fragment。</p>
 */
public class HabitsFragment extends Fragment implements HabitAdapter.Callback {

    private ViewDashboardHabitsBinding binding;
    private DashboardViewModel viewModel;
    private HabitAdapter habitAdapter;

    private final List<HabitItem> allHabits = new ArrayList<>();
    private String selectedCategory = "全部";
    private String today = HabitUtils.today();

    private final Executor diskIO = AppExecutors.getInstance().diskIO();
    private final Executor mainThread = AppExecutors.getInstance().mainThread();

    // ---- launcher ----
    private ActivityResultLauncher<Intent> editorLauncher;
    private ActivityResultLauncher<com.journeyapps.barcodescanner.ScanOptions> habitScanLauncher;
    private ActivityResultLauncher<String> storagePermissionLauncher;
    private ActivityResultLauncher<String> checkInGalleryLauncher;
    private ActivityResultLauncher<Uri> checkInCameraLauncher;
    private ActivityResultLauncher<String> checkInCameraPermissionLauncher;

    // ---- 打卡弹层临时状态 ----
    private SheetCheckInBinding activeCheckInBinding;
    private long checkInHabitId = -1L;
    private String checkInDate;
    private int checkInMood;
    private String checkInPhotoUri;
    // 打开弹层时若当天已有打卡记录，记下其 DB 里的原照片路径。用于区分「本次新拍的临时照片」
    // 与「DB 仍引用的原照片」：取消/换图时只删临时图，绝不误删 DB 原照片（原照片的替换删除
    // 交由保存路径的 upsertCheckIn 处理）。null 表示当天无原照片。
    private String checkInOriginalPhotoUri;
    private boolean checkInSaved;
    private String pendingCheckInCameraPath;
    // 打卡弹层「代」计数：每开一个新弹层自增，关闭时也自增。相册/相机异步回调捕获发起时的代号，
    // 完成时若与当前代号不符（弹层已换/已关），说明结果属于过期弹层——立即删除复制出的图片、
    // 绝不串写进新弹层，杜绝跨弹层图片错配与未引用图残留。
    private int checkInGeneration;

    // 等待存储权限授权后再保存的二维码（仅 API 26-28 用得到）
    private Bitmap pendingSaveQrBitmap;
    private String pendingSaveQrTitle;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerLaunchers();
    }

    private void registerLaunchers() {
        editorLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> viewModel.reload());

        habitScanLauncher = registerForActivityResult(
                new com.journeyapps.barcodescanner.ScanContract(),
                result -> {
                    if (result != null && result.getContents() != null) {
                        handleScannedContent(result.getContents());
                    }
                });

        storagePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted && pendingSaveQrBitmap != null) {
                        saveQrToGallery(pendingSaveQrBitmap, pendingSaveQrTitle);
                    } else if (!granted) {
                        toast(getString(R.string.toast_need_storage_permission_save));
                    }
                    pendingSaveQrBitmap = null;
                    pendingSaveQrTitle = null;
                });

        checkInGalleryLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri == null) {
                        return;
                    }
                    // 捕获发起相册选择时的弹层代号与 binding：异步复制完成后据此判断结果是否仍属当前弹层。
                    final int generation = checkInGeneration;
                    final SheetCheckInBinding target = activeCheckInBinding;
                    diskIO.execute(() -> {
                        String copied = viewModel.repository().copyGalleryImage(uri);
                        if (copied == null) {
                            return;
                        }
                        mainThread.execute(() -> {
                            if (!isAdded() || CheckInCallbackGuard.isStale(
                                    generation, checkInGeneration, target, activeCheckInBinding)) {
                                // 弹层已关/已换：复制出的图成了未引用文件，删掉，绝不写进当前新弹层。
                                diskIO.execute(() -> viewModel.repository().deletePhoto(copied));
                                return;
                            }
                            updateCheckInPhoto(copied);
                        });
                    });
                });

        checkInCameraLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    final String pending = pendingCheckInCameraPath;
                    pendingCheckInCameraPath = null;
                    if (pending == null) {
                        return;
                    }
                    // 拍照失败/页面已销毁/弹层已关：临时文件成了未引用文件，删掉，不落库。
                    if (success == null || !success || !isAdded() || activeCheckInBinding == null) {
                        diskIO.execute(() -> viewModel.repository().deletePhoto(pending));
                        return;
                    }
                    final int generation = checkInGeneration;
                    final SheetCheckInBinding target = activeCheckInBinding;
                    diskIO.execute(() -> {
                        String uri = viewModel.repository().persistCapturedPhoto(pending);
                        if (uri == null) {
                            return;
                        }
                        mainThread.execute(() -> {
                            if (!isAdded() || CheckInCallbackGuard.isStale(
                                    generation, checkInGeneration, target, activeCheckInBinding)) {
                                // 落库期间弹层已关/已换：删掉持久化出的图，绝不串写进新弹层。
                                diskIO.execute(() -> viewModel.repository().deletePhoto(uri));
                                return;
                            }
                            updateCheckInPhoto(uri);
                        });
                    });
                });

        checkInCameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        launchCheckInCamera();
                    } else {
                        toast(getString(R.string.toast_camera_permission_required));
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = ViewDashboardHabitsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(DashboardViewModel.class);

        habitAdapter = new HabitAdapter(this);
        binding.rvHabits.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvHabits.setAdapter(habitAdapter);

        buildCategoryChips();
        binding.etSearchHabits.addTextChangedListener(simpleWatcher(this::applyHabitFilters));

        // FAB（新建/扫码）在 activity_main 顶层、属 DashboardActivity 管理（切到本页才显示），
        // 其点击由宿主转交给下面两个 public 方法，故这里不绑定 FAB。

        viewModel.getHabits().observe(getViewLifecycleOwner(), list -> {
            today = HabitUtils.today();
            allHabits.clear();
            if (list != null) {
                allHabits.addAll(list);
            }
            applyHabitFilters();
            updateSummarySection();
        });
    }

    /** 宿主切到本页时刷新激励语。 */
    public void onPageShown() {
        refreshSlogan();
    }

    private void applyHabitFilters() {
        String query = getText(binding.etSearchHabits);
        List<HabitItem> filtered = HabitUtils.filterHabits(allHabits, query, selectedCategory);
        habitAdapter.submitList(filtered, today);
        binding.tvEmptyHabits.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
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
        binding.tvSummaryHabitCount.setText(String.valueOf(allHabits.size()));
        binding.tvSummaryTodayCount.setText(String.valueOf(completedToday));
        binding.tvSummaryBestStreak.setText(getString(R.string.stat_streak_days_nospace, bestStreak));
    }

    private void refreshSlogan() {
        if (binding == null) {
            return;
        }
        String[] quotes = getResources().getStringArray(R.array.motivational_quotes);
        binding.tvSummarySlogan.setText(quotes[new Random().nextInt(quotes.length)]);
    }

    private void buildCategoryChips() {
        binding.chipGroupCategories.removeAllViews();
        for (String category : HabitUtils.categories()) {
            Chip chip = new Chip(requireContext());
            chip.setText(category);
            chip.setCheckable(true);
            chip.setTag(category);
            chip.setEnsureMinTouchTargetSize(false);
            chip.setOnClickListener(v -> {
                selectedCategory = String.valueOf(chip.getTag());
                applyHabitFilters();
            });
            binding.chipGroupCategories.addView(chip);
            if ("全部".equals(category)) {
                chip.setChecked(true);
            }
        }
    }

    // ---- 新建 / 模板 / 扫码 ----

    public void showTemplateChooser() {
        com.streak.app.databinding.SheetTemplateChooserBinding sheetBinding =
                com.streak.app.databinding.SheetTemplateChooserBinding.inflate(getLayoutInflater());
        ViewGroup container = sheetBinding.layoutTemplateContent;
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());

        addTemplateRow(container, getString(R.string.template_blank_title),
                getString(R.string.template_blank_desc), () -> {
                    dialog.dismiss();
                    openBlankEditorWithSelectedCategory();
                });

        int shown = 0;
        for (HabitTemplate template : HabitTemplate.presets()) {
            if (!"全部".equals(selectedCategory)
                    && !selectedCategory.equals(template.getCategory())) {
                continue;
            }
            String desc = getString(R.string.template_row_desc, template.getCategory(), template.getReminderTime());
            addTemplateRow(container, template.getTitle(), desc, () -> {
                dialog.dismiss();
                openEditorWithTemplate(template);
            });
            shown++;
        }
        if (shown == 0) {
            addTemplateRow(container, getString(R.string.template_empty_category_title),
                    getString(R.string.template_empty_category_desc), () -> {});
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

    private void openEditor(long habitId) {
        Intent intent = new Intent(requireContext(), HabitEditorActivity.class);
        if (habitId > 0) {
            intent.putExtra(HabitEditorActivity.EXTRA_HABIT_ID, habitId);
        }
        editorLauncher.launch(intent);
    }

    private void openBlankEditorWithSelectedCategory() {
        Intent intent = new Intent(requireContext(), HabitEditorActivity.class);
        if (!"全部".equals(selectedCategory)) {
            intent.putExtra(HabitEditorActivity.EXTRA_TPL_CATEGORY, selectedCategory);
        }
        editorLauncher.launch(intent);
    }

    private void openEditorWithTemplate(HabitTemplate template) {
        Intent intent = new Intent(requireContext(), HabitEditorActivity.class)
                .putExtra(HabitEditorActivity.EXTRA_TPL_TITLE, template.getTitle())
                .putExtra(HabitEditorActivity.EXTRA_TPL_CONTENT, template.getContent())
                .putExtra(HabitEditorActivity.EXTRA_TPL_CATEGORY, template.getCategory())
                .putExtra(HabitEditorActivity.EXTRA_TPL_REMINDER, template.getReminderTime())
                .putExtra(HabitEditorActivity.EXTRA_TPL_TAGS, template.tagsText());
        editorLauncher.launch(intent);
    }

    public void launchCameraScan() {
        com.journeyapps.barcodescanner.ScanOptions options = new com.journeyapps.barcodescanner.ScanOptions();
        options.setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE);
        options.setPrompt(getString(R.string.scan_prompt_habit));
        options.setBeepEnabled(false);
        options.setOrientationLocked(true);
        options.setCaptureActivity(com.streak.app.ui.PortraitCaptureActivity.class);
        habitScanLauncher.launch(options);
    }

    private void handleScannedContent(String raw) {
        HabitQrCodec.Decoded decoded = HabitQrCodec.decode(raw);
        if (decoded == null) {
            toast(getString(R.string.toast_invalid_habit_qr));
            return;
        }
        openEditorWithScan(decoded);
    }

    private void openEditorWithScan(HabitQrCodec.Decoded decoded) {
        Intent intent = new Intent(requireContext(), HabitEditorActivity.class)
                .putExtra(HabitEditorActivity.EXTRA_TPL_TITLE, decoded.title)
                .putExtra(HabitEditorActivity.EXTRA_TPL_CONTENT, decoded.content)
                .putExtra(HabitEditorActivity.EXTRA_TPL_CATEGORY, decoded.category)
                .putExtra(HabitEditorActivity.EXTRA_TPL_REMINDER, decoded.reminderTime)
                .putExtra(HabitEditorActivity.EXTRA_TPL_TAGS, decoded.tags);
        editorLauncher.launch(intent);
    }

    // ---- HabitAdapter.Callback ----

    @Override
    public void onToggleComplete(HabitItem item) {
        boolean doneToday = item.getCompletedDates() != null && item.getCompletedDates().contains(today);
        if (doneToday) {
            undoTodayCheckIn(item.getId());
        } else {
            promptCheckInNote(item);
        }
    }

    @Override
    public void onEdit(HabitItem item) {
        openEditor(item.getId());
    }

    @Override
    public void onShare(HabitItem item) {
        showHabitQr(item);
    }

    @Override
    public void onDelete(HabitItem item) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_delete_habit_title)
                .setMessage(getString(R.string.dialog_delete_habit_message, item.getTitle()))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    final long habitId = item.getId();
                    // 进后台前捕获 application context：后台任务里不能再调 requireContext()，
                    // 页面若在删除期间被销毁/旋转会抛 IllegalStateException。
                    final android.content.Context appContext = requireContext().getApplicationContext();
                    diskIO.execute(() -> {
                        if (viewModel.repository().deleteHabitById(habitId)) {
                            viewModel.repository().cancelReminder(habitId);
                        }
                        StreakWidgetProvider.refreshAll(appContext);
                        mainThread.execute(() -> {
                            if (isAdded()) {
                                viewModel.reload();
                            }
                        });
                    });
                })
                .show();
    }

    @Override
    public void onOpenDetail(HabitItem item) {
        startActivity(HabitDetailActivity.newIntent(requireContext(), item.getId()));
    }

    // ---- 打卡录入弹层 ----

    private void promptCheckInNote(HabitItem item) {
        final String date = HabitUtils.today();
        SheetCheckInBinding sheetBinding = SheetCheckInBinding.inflate(getLayoutInflater());

        checkInGeneration++;
        activeCheckInBinding = sheetBinding;
        checkInHabitId = item.getId();
        checkInDate = date;
        checkInMood = 0;
        checkInPhotoUri = null;
        // 本次弹层开始时尚不知 DB 原照片，预填拿到记录后再赋值。
        checkInOriginalPhotoUri = null;

        sheetBinding.tvCheckInTitle.setText(getString(R.string.dialog_checkin_title, item.getTitle()));

        // 若今天已存在记录（重复打卡/补充信息），预填其心情/耗时/备注/照片
        diskIO.execute(() -> {
            CheckInRecord existing = viewModel.repository().getCheckIn(item.getId(), date);
            if (existing != null) {
                mainThread.execute(() -> {
                    if (activeCheckInBinding != sheetBinding) {
                        return;
                    }
                    checkInMood = existing.getMood();
                    highlightMood(sheetBinding, existing.getMood());
                    if (existing.getDurationMinutes() > 0) {
                        sheetBinding.etCheckInDuration.setText(String.valueOf(existing.getDurationMinutes()));
                    }
                    if (existing.getNote() != null) {
                        sheetBinding.etCheckInNote.setText(existing.getNote());
                    }
                    if (existing.getPhotoUri() != null) {
                        // 记下 DB 里已落库的原照片：弹层期间绝不删它（取消/换图都不删），
                        // 它的删除只由保存时的 upsertCheckIn 在真正替换后负责，避免取消后
                        // DB 仍引用一个已被删掉的文件。
                        checkInOriginalPhotoUri = existing.getPhotoUri();
                        updateCheckInPhoto(existing.getPhotoUri());
                    }
                });
            }
        });

        TextView[] moods = {
                sheetBinding.tvMood1, sheetBinding.tvMood2, sheetBinding.tvMood3,
                sheetBinding.tvMood4, sheetBinding.tvMood5
        };
        for (int i = 0; i < moods.length; i++) {
            final int level = i + 1;
            moods[i].setOnClickListener(v -> {
                checkInMood = (checkInMood == level) ? 0 : level;
                highlightMood(sheetBinding, checkInMood);
            });
        }

        sheetBinding.btnCheckInTakePhoto.setOnClickListener(v -> openCheckInCamera());
        sheetBinding.btnCheckInPickPhoto.setOnClickListener(v -> checkInGalleryLauncher.launch("image/*"));
        sheetBinding.btnCheckInRemovePhoto.setOnClickListener(v -> removeCheckInPhoto());

        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        dialog.setContentView(sheetBinding.getRoot());
        dialog.setOnDismissListener(d -> {
            if (activeCheckInBinding == sheetBinding) {
                // 取消/未保存关闭：只删本次新拍/新选的临时图，绝不删 DB 已落库的原照片
                //（原照片仍被 DB 引用，删了会留下悬空引用）。
                if (!checkInSaved && checkInPhotoUri != null
                        && !checkInPhotoUri.equals(checkInOriginalPhotoUri)) {
                    final String orphan = checkInPhotoUri;
                    diskIO.execute(() -> viewModel.repository().deletePhoto(orphan));
                }
                checkInGeneration++;
                activeCheckInBinding = null;
                checkInPhotoUri = null;
                checkInOriginalPhotoUri = null;
                checkInSaved = false;
            }
        });

        sheetBinding.btnCheckInSkip.setOnClickListener(v -> {
            // 「跳过」= 仅记一条最简打卡（无心情/耗时/备注/照片）。未改照片，photoChanged=false。
            sheetBinding.btnCheckInSkip.setEnabled(false);
            sheetBinding.btnCheckInSave.setEnabled(false);
            writeCheckIn(item.getId(), date, 0, 0, null, null,
                    checkInOriginalPhotoUri, false,
                    () -> {
                        checkInSaved = true;
                        dialog.dismiss();
                    },
                    () -> {
                        sheetBinding.btnCheckInSkip.setEnabled(true);
                        sheetBinding.btnCheckInSave.setEnabled(true);
                        toast(getString(R.string.toast_save_failed_retry));
                    });
        });
        sheetBinding.btnCheckInSave.setOnClickListener(v -> {
            int duration = parseDuration(sheetBinding.etCheckInDuration.getText());
            String note = String.valueOf(sheetBinding.etCheckInNote.getText()).trim();
            final boolean photoChanged = isCheckInPhotoChanged();
            final String expectedOriginal = checkInOriginalPhotoUri;
            // 保存期间禁用两按钮，防重复点击提交多次；结果回来再据成功/失败决定关弹层或恢复。
            sheetBinding.btnCheckInSkip.setEnabled(false);
            sheetBinding.btnCheckInSave.setEnabled(false);
            writeCheckIn(item.getId(), date, checkInMood, duration,
                    note.isEmpty() ? null : note, checkInPhotoUri,
                    expectedOriginal, photoChanged,
                    () -> {
                        checkInSaved = true;
                        dialog.dismiss();
                    },
                    () -> {
                        // 写入失败（乐观并发被拒/DB 失败）：恢复按钮、提示重试、不关弹层，
                        // 本次临时图保留以便重试，最终取消关闭时由 onDismiss 统一清理不留孤儿。
                        sheetBinding.btnCheckInSkip.setEnabled(true);
                        sheetBinding.btnCheckInSave.setEnabled(true);
                        toast(getString(R.string.toast_save_failed_retry));
                    });
        });
        dialog.show();
    }

    /** 高亮选中的心情表情，其余取消选中。level=0 表示全不选。 */
    private void highlightMood(SheetCheckInBinding b, int level) {
        TextView[] moods = {b.tvMood1, b.tvMood2, b.tvMood3, b.tvMood4, b.tvMood5};
        for (int i = 0; i < moods.length; i++) {
            moods[i].setSelected(i + 1 == level);
        }
    }

    /** 从耗时输入解析非负分钟数，非法/空返回 0。 */
    private int parseDuration(CharSequence text) {
        try {
            int value = Integer.parseInt(String.valueOf(text).trim());
            return Math.max(0, value);
        } catch (Exception e) {
            return 0;
        }
    }

    private void openCheckInCamera() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchCheckInCamera();
        } else {
            checkInCameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchCheckInCamera() {
        CameraCaptureInfo info = viewModel.repository().createCameraCapture();
        pendingCheckInCameraPath = info.getFilePath();
        checkInCameraLauncher.launch(info.getUri());
    }

    /**
     * 更新弹层里的打卡照片预览；替换旧的<b>临时</b>照片时删除它。
     *
     * <p>关键：被替换的旧值若等于 DB 原照片（{@code checkInOriginalPhotoUri}），绝不删——
     * 它仍被数据库引用，弹层期间只是预览，删了会造成 DB 悬空引用。原照片的删除只在保存时
     * 由 {@code upsertCheckIn} 真正替换后负责。只有「本次新拍/新选、尚未落库」的临时图才即删。</p>
     */
    private void updateCheckInPhoto(String imageUri) {
        if (checkInPhotoUri != null && !checkInPhotoUri.equals(imageUri)
                && !checkInPhotoUri.equals(checkInOriginalPhotoUri)) {
            final String orphan = checkInPhotoUri;
            diskIO.execute(() -> viewModel.repository().deletePhoto(orphan));
        }
        checkInPhotoUri = imageUri;
        if (activeCheckInBinding != null) {
            ImageLoader.load(activeCheckInBinding.ivCheckInPhoto, imageUri, 720);
            activeCheckInBinding.ivCheckInPhoto.setVisibility(View.VISIBLE);
            activeCheckInBinding.btnCheckInRemovePhoto.setVisibility(View.VISIBLE);
        }
    }

    private void removeCheckInPhoto() {
        // 只删本次新拍/新选的临时图；DB 原照片不在此删（用户点「移除」后若保存，
        // upsertCheckIn 会以 photoUri=null 落库并删原文件；若取消则原照片应保持）。
        if (checkInPhotoUri != null && !checkInPhotoUri.equals(checkInOriginalPhotoUri)) {
            final String orphan = checkInPhotoUri;
            diskIO.execute(() -> viewModel.repository().deletePhoto(orphan));
        }
        checkInPhotoUri = null;
        if (activeCheckInBinding != null) {
            activeCheckInBinding.ivCheckInPhoto.setImageDrawable(null);
            activeCheckInBinding.ivCheckInPhoto.setVisibility(View.GONE);
            activeCheckInBinding.btnCheckInRemovePhoto.setVisibility(View.GONE);
        }
    }

    /** 本次弹层用户是否改动过照片（当前选择 != 打开时 DB 原照片）。 */
    private boolean isCheckInPhotoChanged() {
        return !java.util.Objects.equals(checkInPhotoUri, checkInOriginalPhotoUri);
    }

    /**
     * 后台直写打卡并把「实际写入结果」回传 UI：仅当 upsertCheckIn 返回 true（真正落库）才走
     * onSuccess（置已保存/关弹层/刷新）；返回 false（乐观并发被拒或写失败）走 onFailure
     * （恢复按钮、提示重试、不关弹层）。避免「DB 没写成，UI 却已关弹层并显示已打卡」。
     */
    private void writeCheckIn(long habitId, String date, int mood,
                             int durationMinutes, String note, String photoUri,
                             String expectedOriginalPhotoUri, boolean photoChanged,
                             Runnable onSuccess, Runnable onFailure) {
        // 先在主线程取应用级 Context：后台任务里再调 requireContext() 若页面已销毁会抛
        // IllegalStateException。appContext 生命周期与进程一致，可安全跨线程持有。
        final android.content.Context appContext = requireContext().getApplicationContext();
        diskIO.execute(() -> {
            boolean ok = viewModel.repository().upsertCheckIn(habitId, date, mood, durationMinutes,
                    note, photoUri, expectedOriginalPhotoUri, photoChanged);
            if (ok) {
                StreakWidgetProvider.refreshAll(appContext);
            }
            mainThread.execute(() -> {
                if (!isAdded()) {
                    return;
                }
                if (ok) {
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                    viewModel.reload();
                } else if (onFailure != null) {
                    onFailure.run();
                }
            });
        });
    }

    private void undoTodayCheckIn(long habitId) {
        final String todayDate = HabitUtils.today();
        final android.content.Context appContext = requireContext().getApplicationContext();
        diskIO.execute(() -> {
            viewModel.repository().removeCheckIn(habitId, todayDate);
            StreakWidgetProvider.refreshAll(appContext);
            mainThread.execute(() -> {
                if (isAdded()) {
                    viewModel.reload();
                }
            });
        });
    }

    // ---- 二维码分享 ----

    private void showHabitQr(HabitItem item) {
        SheetHabitQrBinding sheetBinding = SheetHabitQrBinding.inflate(getLayoutInflater());
        sheetBinding.tvQrHabitTitle.setText(item.getTitle());

        int sizePx = (int) (240 * getResources().getDisplayMetrics().density);
        Bitmap qr = QrGenerator.generate(HabitQrCodec.encode(item), sizePx);
        if (qr == null) {
            toast(getString(R.string.toast_qr_generate_failed));
            return;
        }
        sheetBinding.ivQrImage.setImageBitmap(qr);
        sheetBinding.btnSaveQr.setOnClickListener(v -> requestSaveQr(qr, item.getTitle()));

        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        dialog.setContentView(sheetBinding.getRoot());
        dialog.show();
    }

    private void requestSaveQr(Bitmap qr, String title) {
        if (qr == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingSaveQrBitmap = qr;
            pendingSaveQrTitle = title;
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        saveQrToGallery(qr, title);
    }

    private void saveQrToGallery(Bitmap qr, String title) {
        toast(getString(R.string.toast_saving_to_album));
        diskIO.execute(() -> {
            Uri saved = viewModel.repository().saveQrToGallery(qr, title);
            mainThread.execute(() -> {
                if (isAdded()) {
                    toast(saved != null ? getString(R.string.toast_saved_to_album)
                            : getString(R.string.toast_save_failed_retry));
                }
            });
        });
    }

    // ---- helpers ----

    private String getText(TextView textView) {
        return String.valueOf(textView.getText()).trim();
    }

    private android.text.TextWatcher simpleWatcher(Runnable callback) {
        return new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                callback.run();
            }
        };
    }

    private void toast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        activeCheckInBinding = null;
    }
}
