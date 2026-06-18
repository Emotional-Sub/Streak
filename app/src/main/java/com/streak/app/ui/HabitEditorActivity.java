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

    private ActivityHabitEditorBinding binding;
    private AppRepository repository;
    private long editingHabitId = -1L;
    private HabitItem originalHabit;
    private String currentImageUri;
    private String pendingCameraPath;
    private ActivityResultLauncher<String> galleryLauncher;
    private ActivityResultLauncher<Uri> cameraLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityHabitEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = new AppRepository(this);
        editingHabitId = getIntent().getLongExtra(EXTRA_HABIT_ID, -1L);
        originalHabit = editingHabitId > 0 ? repository.findHabitById(editingHabitId) : null;

        setupToolbar();
        setupCategoryDropdown();
        setupActivityResultLaunchers();
        setupActions();
        bindHabitData();
    }

    private void setupToolbar() {
        binding.toolbarEditor.setNavigationOnClickListener(v -> finish());
        binding.toolbarEditor.setTitle(originalHabit == null ? "新增习惯" : "编辑习惯");
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
    }

    private void setupActivityResultLaunchers() {
        galleryLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) {
                return;
            }
            String copied = repository.copyGalleryImage(uri);
            if (copied == null) {
                Toast.makeText(this, "图片选择失败", Toast.LENGTH_SHORT).show();
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
            binding.etEditorReminderTime.setText("20:00");
            binding.switchReminder.setChecked(true);
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
            binding.ivEditorPreview.setImageURI(Uri.parse(currentImageUri));
            binding.ivEditorPreview.setVisibility(android.view.View.VISIBLE);
            binding.btnRemovePhoto.setVisibility(android.view.View.VISIBLE);
        }
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
        binding.ivEditorPreview.setImageURI(Uri.parse(imageUri));
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
            Toast.makeText(this, "请先填写标题和内容", Toast.LENGTH_SHORT).show();
            return;
        }
        if (category.isEmpty()) {
            category = "学习";
        }
        if (reminderTime.isEmpty()) {
            reminderTime = "20:00";
        }

        List<HabitItem> habits = repository.readHabits();
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
        if (originalHabit == null) {
            item.setId(System.currentTimeMillis());
            item.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            item.setCompletedDates(new ArrayList<>());
        }
        item.setTitle(title);
        item.setContent(content);
        item.setCategory(category);
        item.setReminderTime(reminderTime);
        item.setTags(tags);
        item.setReminderEnabled(binding.switchReminder.isChecked());
        item.setImageUri(currentImageUri);

        if (originalHabit != null && !TextUtils.equals(originalHabit.getImageUri(), currentImageUri)) {
            repository.deletePhoto(originalHabit.getImageUri());
        }

        boolean replaced = false;
        for (int i = 0; i < habits.size(); i++) {
            if (habits.get(i).getId() == item.getId()) {
                habits.set(i, item);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            habits.add(item);
        }

        repository.writeHabits(habits);
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
