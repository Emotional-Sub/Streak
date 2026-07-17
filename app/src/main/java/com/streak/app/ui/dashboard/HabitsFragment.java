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
    private boolean checkInSaved;
    private String pendingCheckInCameraPath;

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
                    if (uri != null) {
                        diskIO.execute(() -> {
                            String copied = viewModel.repository().copyGalleryImage(uri);
                            mainThread.execute(() -> {
                                if (copied != null && isAdded()) {
                                    updateCheckInPhoto(copied);
                                }
                            });
                        });
                    }
                });

        checkInCameraLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    if (success != null && success && pendingCheckInCameraPath != null) {
                        String uri = viewModel.repository().persistCapturedPhoto(pendingCheckInCameraPath);
                        pendingCheckInCameraPath = null;
                        if (uri != null) {
                            updateCheckInPhoto(uri);
                        }
                    }
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
                    final String imageUri = item.getImageUri();
                    diskIO.execute(() -> {
                        viewModel.repository().deleteHabitById(habitId);
                        viewModel.repository().cancelReminder(habitId);
                        viewModel.repository().deletePhoto(imageUri);
                        StreakWidgetProvider.refreshAll(requireContext().getApplicationContext());
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

        activeCheckInBinding = sheetBinding;
        checkInHabitId = item.getId();
        checkInDate = date;
        checkInMood = 0;
        checkInPhotoUri = null;

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
                if (!checkInSaved && checkInPhotoUri != null) {
                    viewModel.repository().deletePhoto(checkInPhotoUri);
                }
                activeCheckInBinding = null;
                checkInPhotoUri = null;
                checkInSaved = false;
            }
        });

        sheetBinding.btnCheckInSkip.setOnClickListener(v -> {
            checkInSaved = true;
            writeCheckIn(item.getId(), date, 0, 0, null, null);
            dialog.dismiss();
        });
        sheetBinding.btnCheckInSave.setOnClickListener(v -> {
            int duration = parseDuration(sheetBinding.etCheckInDuration.getText());
            String note = String.valueOf(sheetBinding.etCheckInNote.getText()).trim();
            checkInSaved = true;
            writeCheckIn(item.getId(), date, checkInMood, duration,
                    note.isEmpty() ? null : note, checkInPhotoUri);
            dialog.dismiss();
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

    /** 更新弹层里的打卡照片预览；替换旧的未落库照片时删除它。 */
    private void updateCheckInPhoto(String imageUri) {
        if (checkInPhotoUri != null && !checkInPhotoUri.equals(imageUri)) {
            viewModel.repository().deletePhoto(checkInPhotoUri);
        }
        checkInPhotoUri = imageUri;
        if (activeCheckInBinding != null) {
            ImageLoader.load(activeCheckInBinding.ivCheckInPhoto, imageUri, 720);
            activeCheckInBinding.ivCheckInPhoto.setVisibility(View.VISIBLE);
            activeCheckInBinding.btnCheckInRemovePhoto.setVisibility(View.VISIBLE);
        }
    }

    private void removeCheckInPhoto() {
        if (checkInPhotoUri != null) {
            viewModel.repository().deletePhoto(checkInPhotoUri);
            checkInPhotoUri = null;
        }
        if (activeCheckInBinding != null) {
            activeCheckInBinding.ivCheckInPhoto.setImageDrawable(null);
            activeCheckInBinding.ivCheckInPhoto.setVisibility(View.GONE);
            activeCheckInBinding.btnCheckInRemovePhoto.setVisibility(View.GONE);
        }
    }

    private void writeCheckIn(long habitId, String date, int mood,
                             int durationMinutes, String note, String photoUri) {
        diskIO.execute(() -> {
            viewModel.repository().upsertCheckIn(habitId, date, mood, durationMinutes, note, photoUri);
            StreakWidgetProvider.refreshAll(requireContext().getApplicationContext());
            mainThread.execute(() -> {
                if (isAdded()) {
                    viewModel.reload();
                }
            });
        });
    }

    private void undoTodayCheckIn(long habitId) {
        final String todayDate = HabitUtils.today();
        diskIO.execute(() -> {
            viewModel.repository().removeCheckIn(habitId, todayDate);
            StreakWidgetProvider.refreshAll(requireContext().getApplicationContext());
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
