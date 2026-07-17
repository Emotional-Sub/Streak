package com.streak.app.ui;

import android.app.TimePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.streak.app.R;
import com.streak.app.databinding.ActivityHabitEditorBinding;
import com.streak.app.model.CameraCaptureInfo;
import com.streak.app.model.HabitItem;
import com.streak.app.storage.AppRepository;
import com.streak.app.util.HabitUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class HabitEditorActivity extends AppCompatActivity {
    public static final String EXTRA_HABIT_ID = "extra_habit_id";
    public static final String EXTRA_TPL_TITLE = "extra_tpl_title";
    public static final String EXTRA_TPL_CONTENT = "extra_tpl_content";
    public static final String EXTRA_TPL_CATEGORY = "extra_tpl_category";
    public static final String EXTRA_TPL_REMINDER = "extra_tpl_reminder";
    public static final String EXTRA_TPL_TAGS = "extra_tpl_tags";

    private ActivityHabitEditorBinding binding;
    private AppRepository repository;
    private long editingHabitId = -1L;
    private HabitItem originalHabit;
    private String currentImageUri;
    private String pendingCameraPath;
    private ActivityResultLauncher<String> galleryLauncher;
    private ActivityResultLauncher<Uri> cameraLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityHabitEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = new AppRepository(this);
        editingHabitId = getIntent().getLongExtra(EXTRA_HABIT_ID, -1L);
        originalHabit = editingHabitId > 0 ? repository.findHabitById(editingHabitId) : null;

        // 意图编辑（带了有效 id）但习惯已不存在（可能已被删除/是过期引用）：
        // 直接提示并退出，避免静默地生成新 id 建一条重复习惯。
        if (editingHabitId > 0 && originalHabit == null) {
            Toast.makeText(this, R.string.toast_habit_not_found, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupToolbar();
        setupCategoryDropdown();
        setupActivityResultLaunchers();
        setupActions();
        bindHabitData();

        // 恢复因旋转/进程回收丢失的临时状态
        if (savedInstanceState != null) {
            pendingCameraPath = savedInstanceState.getString("pending_camera_path");
            if (savedInstanceState.containsKey("current_image_uri")) {
                String savedImage = savedInstanceState.getString("current_image_uri");
                if (TextUtils.isEmpty(savedImage)) {
                    removeImage();
                } else {
                    currentImageUri = savedImage;
                    com.streak.app.util.ImageLoader.load(binding.ivEditorPreview, savedImage, 720);
                    binding.ivEditorPreview.setVisibility(android.view.View.VISIBLE);
                    binding.btnRemovePhoto.setVisibility(android.view.View.VISIBLE);
                }
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("pending_camera_path", pendingCameraPath);
        outState.putString("current_image_uri", currentImageUri);
    }

    private void setupToolbar() {
        binding.toolbarEditor.setNavigationOnClickListener(v -> finish());
        binding.toolbarEditor.setTitle(getString(originalHabit == null ? R.string.editor_title_new : R.string.editor_title_edit));
    }

    // 目标周期下拉项，索引即 weeklyTarget：0=每天，1..6=每周 N 次
    private String[] goalOptions() {
        return getResources().getStringArray(R.array.weekly_target_options);
    }

    private void setupCategoryDropdown() {
        List<String> categories = new ArrayList<>(HabitUtils.categories());
        categories.remove("全部");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_dropdown_item_1line,
                categories
        );
        binding.actEditorCategory.setAdapter(adapter);
        if (originalHabit == null) {
            binding.actEditorCategory.setText("学习", false);
        }

        // 目标周期下拉
        String[] goalOptions = goalOptions();
        ArrayAdapter<String> goalAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_dropdown_item_1line, goalOptions);
        binding.actEditorGoal.setAdapter(goalAdapter);
        int target = originalHabit == null ? 0 : originalHabit.getWeeklyTarget();
        if (target < 0 || target >= goalOptions.length) {
            target = 0;
        }
        binding.actEditorGoal.setText(goalOptions[target], false);
    }

    /** 从下拉框文本解析出 weeklyTarget（匹配不到则按每天=0）。 */
    private int readWeeklyTarget() {
        String selected = String.valueOf(binding.actEditorGoal.getText()).trim();
        String[] goalOptions = goalOptions();
        for (int i = 0; i < goalOptions.length; i++) {
            if (goalOptions[i].equals(selected)) {
                return i;
            }
        }
        return 0;
    }

    private void setupActivityResultLaunchers() {
        galleryLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) {
                return;
            }
            String copied = repository.copyGalleryImage(uri);
            if (copied == null) {
                Toast.makeText(this, R.string.toast_image_pick_failed, Toast.LENGTH_SHORT).show();
            } else {
                updateImage(copied);
            }
        });

        cameraLauncher = registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
            String filePath = pendingCameraPath;
            pendingCameraPath = null;
            if (!success || filePath == null) {
                repository.deletePhoto(filePath);
                return;
            }
            String imageUri = repository.persistCapturedPhoto(filePath);
            if (imageUri == null) {
                repository.deletePhoto(filePath);
            } else {
                updateImage(imageUri);
            }
        });

        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        launchCamera();
                    } else {
                        Toast.makeText(this, R.string.toast_camera_permission_required, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void setupActions() {
        binding.etEditorReminderTime.setOnClickListener(v -> showTimePicker());
        binding.btnTakePhoto.setOnClickListener(v -> openCamera());
        binding.btnPickPhoto.setOnClickListener(v -> galleryLauncher.launch("image/*"));
        binding.btnRemovePhoto.setOnClickListener(v -> removeImage());
        binding.btnSaveHabit.setOnClickListener(v -> saveHabit());
    }

    private void bindHabitData() {
        if (originalHabit == null) {
            applyTemplateIfPresent();
            return;
        }
        binding.etEditorTitle.setText(originalHabit.getTitle());
        binding.etEditorContent.setText(originalHabit.getContent());
        binding.actEditorCategory.setText(originalHabit.getCategory(), false);
        binding.etEditorTags.setText(TextUtils.join(", ", originalHabit.getTags()));
        binding.etEditorReminderTime.setText(originalHabit.getReminderTime());
        binding.switchReminder.setChecked(originalHabit.isReminderEnabled());
        if (!TextUtils.isEmpty(originalHabit.getImageUri())) {
            currentImageUri = originalHabit.getImageUri();
            com.streak.app.util.ImageLoader.load(binding.ivEditorPreview, currentImageUri, 720);
            binding.ivEditorPreview.setVisibility(android.view.View.VISIBLE);
            binding.btnRemovePhoto.setVisibility(android.view.View.VISIBLE);
        }
    }

    private void applyTemplateIfPresent() {
        Intent intent = getIntent();
        String tplTitle = intent.getStringExtra(EXTRA_TPL_TITLE);

        binding.etEditorReminderTime.setText(
                intent.getStringExtra(EXTRA_TPL_REMINDER) != null
                        ? intent.getStringExtra(EXTRA_TPL_REMINDER) : "20:00"
        );
        binding.switchReminder.setChecked(true);

        // 分类：空白新建也可能带分类（习惯页选中了具体分类时），故先于 tplTitle 判空处理。
        String category = intent.getStringExtra(EXTRA_TPL_CATEGORY);
        if (category != null) {
            binding.actEditorCategory.setText(category, false);
        }

        if (tplTitle == null) {
            // 空白新建：标题/内容/标签留空，分类已在上方按选中项应用（未带则沿用默认）。
            return;
        }
        binding.etEditorTitle.setText(tplTitle);
        binding.etEditorContent.setText(intent.getStringExtra(EXTRA_TPL_CONTENT));
        binding.etEditorTags.setText(intent.getStringExtra(EXTRA_TPL_TAGS));
    }

    private void showTimePicker() {
        String currentValue = String.valueOf(binding.etEditorReminderTime.getText());
        int hour = 20;
        int minute = 0;
        if (currentValue.contains(":")) {
            try {
                String[] parts = currentValue.split(":");
                hour = Integer.parseInt(parts[0]);
                minute = Integer.parseInt(parts[1]);
            } catch (Exception ignored) {
            }
        }
        new TimePickerDialog(
                this,
                (view, selectedHour, selectedMinute) -> binding.etEditorReminderTime.setText(
                        String.format(Locale.CHINA, "%02d:%02d", selectedHour, selectedMinute)
                ),
                hour,
                minute,
                true
        ).show();
    }

    private void openCamera() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            launchCamera();
        } else {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA);
        }
    }

    private void launchCamera() {
        CameraCaptureInfo captureInfo = repository.createCameraCapture();
        pendingCameraPath = captureInfo.getFilePath();
        cameraLauncher.launch(captureInfo.getUri());
    }

    private void updateImage(String imageUri) {
        if (!TextUtils.isEmpty(currentImageUri)
                && (originalHabit == null || !TextUtils.equals(currentImageUri, originalHabit.getImageUri()))
                && !TextUtils.equals(currentImageUri, imageUri)) {
            repository.deletePhoto(currentImageUri);
        }
        currentImageUri = imageUri;
        com.streak.app.util.ImageLoader.load(binding.ivEditorPreview, imageUri, 720);
        binding.ivEditorPreview.setVisibility(android.view.View.VISIBLE);
        binding.btnRemovePhoto.setVisibility(android.view.View.VISIBLE);
    }

    private void removeImage() {
        if (!TextUtils.isEmpty(currentImageUri)
                && (originalHabit == null || !TextUtils.equals(currentImageUri, originalHabit.getImageUri()))) {
            repository.deletePhoto(currentImageUri);
        }
        currentImageUri = null;
        binding.ivEditorPreview.setImageDrawable(null);
        binding.ivEditorPreview.setVisibility(android.view.View.GONE);
        binding.btnRemovePhoto.setVisibility(android.view.View.GONE);
    }

    private void saveHabit() {
        String title = String.valueOf(binding.etEditorTitle.getText()).trim();
        String content = String.valueOf(binding.etEditorContent.getText()).trim();
        String category = String.valueOf(binding.actEditorCategory.getText()).trim();
        String tagsInput = String.valueOf(binding.etEditorTags.getText()).trim();
        String reminderTime = String.valueOf(binding.etEditorReminderTime.getText()).trim();

        if (title.isEmpty() || content.isEmpty()) {
            Toast.makeText(this, R.string.toast_fill_title_content, Toast.LENGTH_SHORT).show();
            return;
        }
        if (category.isEmpty()) {
            category = "学习";
        }
        if (reminderTime.isEmpty()) {
            reminderTime = "20:00";
        }

        List<String> tags = new ArrayList<>();
        if (!tagsInput.isEmpty()) {
            tags.addAll(Arrays.asList(tagsInput.split(",")));
            List<String> cleaned = new ArrayList<>();
            for (String tag : tags) {
                String trimmed = tag.trim();
                if (!trimmed.isEmpty() && !cleaned.contains(trimmed)) {
                    cleaned.add(trimmed);
                }
            }
            tags = cleaned;
        }

        HabitItem item = originalHabit == null ? new HabitItem() : originalHabit;
        // 先存旧图路径，因为下面 setImageUri 会直接改写 originalHabit 自身
        String previousImageUri = originalHabit == null ? null : originalHabit.getImageUri();
        if (originalHabit == null) {
            // 仅新建时生成防撞 id：走仓库的全表存在性查询，对「所有账号」防撞。
            // 不能只在当前账号的 readHabits() 里查——id 是全表主键，漏查其它账号的
            // 同 id 会让 upsert 的 REPLACE 跨账号覆盖，破坏数据隔离。
            item.setId(repository.generateUniqueHabitId());
            item.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        }
        item.setTitle(title);
        item.setContent(content);
        item.setCategory(category);
        item.setReminderTime(reminderTime);
        item.setTags(tags);
        item.setReminderEnabled(binding.switchReminder.isChecked());
        item.setWeeklyTarget(readWeeklyTarget());
        item.setImageUri(currentImageUri);

        if (originalHabit != null && !TextUtils.equals(previousImageUri, currentImageUri)) {
            repository.deletePhoto(previousImageUri);
        }

        // 只 upsert 这一条（按 id 主键），不整表覆盖——避免并发下用本页的过期快照
        // 抹掉其它习惯的改动（如后台补卡/提醒回执）。id 唯一性由仓库全表防撞保证。
        repository.saveHabit(item);
        repository.syncReminder(item);
        savedHabit = true;
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_HABIT_ID, item.getId()));
        finish();
    }

    private boolean savedHabit = false;

    @Override
    protected void onDestroy() {
        // 未点保存就退出时，清理本次新拍/新选但未落库的临时照片，避免私有目录残留。
        if (isFinishing() && !savedHabit
                && !TextUtils.isEmpty(currentImageUri)
                && (originalHabit == null || !TextUtils.equals(originalHabit.getImageUri(), currentImageUri))) {
            repository.deletePhoto(currentImageUri);
        }
        super.onDestroy();
    }
}
