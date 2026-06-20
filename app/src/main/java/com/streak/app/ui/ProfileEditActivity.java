package com.streak.app.ui;

import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.streak.app.databinding.ActivityProfileEditBinding;
import com.streak.app.model.CameraCaptureInfo;
import com.streak.app.model.UserAccount;
import com.streak.app.storage.AppRepository;
import com.streak.app.util.AvatarPresets;

import java.util.Locale;

public class ProfileEditActivity extends AppCompatActivity {

    private ActivityProfileEditBinding binding;
    private AppRepository repository;
    private String username = "";
    private String currentAvatarUri;
    private String originalAvatarUri;
    private String pendingCameraPath;

    private ActivityResultLauncher<String> galleryLauncher;
    private ActivityResultLauncher<Uri> cameraLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityProfileEditBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = new AppRepository(this);
        username = repository.getCurrentUser();

        setupLaunchers();
        binding.toolbarProfileEdit.setNavigationOnClickListener(v -> finish());
        binding.btnAvatarCamera.setOnClickListener(v -> openCamera());
        binding.btnAvatarGallery.setOnClickListener(v -> galleryLauncher.launch("image/*"));
        binding.btnAvatarRemove.setOnClickListener(v -> removeAvatar());
        binding.btnProfileSave.setOnClickListener(v -> save());

        buildAvatarPresets();
        bindAccount();

        // 恢复因旋转/进程回收丢失的临时状态（覆盖 bindAccount 设置的头像）
        if (savedInstanceState != null) {
            pendingCameraPath = savedInstanceState.getString("pending_camera_path");
            if (savedInstanceState.containsKey("current_avatar_uri")) {
                currentAvatarUri = savedInstanceState.getString("current_avatar_uri");
                renderAvatar();
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("pending_camera_path", pendingCameraPath);
        outState.putString("current_avatar_uri", currentAvatarUri);
    }

    private void setupLaunchers() {
        galleryLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) {
                return;
            }
            String copied = repository.copyAvatarImage(uri);
            if (copied == null) {
                Toast.makeText(this, "头像选择失败", Toast.LENGTH_SHORT).show();
            } else {
                updateAvatar(copied);
            }
        });

        cameraLauncher = registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
            String filePath = pendingCameraPath;
            pendingCameraPath = null;
            if (!success || filePath == null) {
                repository.deletePhoto(filePath);
                return;
            }
            String avatarUri = repository.persistCapturedPhoto(filePath);
            if (avatarUri == null) {
                repository.deletePhoto(filePath);
            } else {
                updateAvatar(avatarUri);
            }
        });

        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        launchCamera();
                    } else {
                        Toast.makeText(this, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void bindAccount() {
        UserAccount account = repository.getAccount(username);
        String displayName = username;
        binding.etProfileUsername.setText(username);
        if (account != null) {
            if (!TextUtils.isEmpty(account.getDisplayName())) {
                displayName = account.getDisplayName();
            }
            binding.etProfileDisplayName.setText(
                    TextUtils.isEmpty(account.getDisplayName()) ? username : account.getDisplayName());
            binding.etProfileMotto.setText(account.getMotto());
            currentAvatarUri = account.getAvatarUri();
            originalAvatarUri = account.getAvatarUri();
        } else {
            binding.etProfileDisplayName.setText(username);
        }
        binding.tvAvatarLetter.setText(
                displayName.isEmpty() ? "U" : displayName.substring(0, 1).toUpperCase(Locale.ROOT));
        renderAvatar();
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

    private void buildAvatarPresets() {
        android.view.ViewGroup container = binding.layoutAvatarPresets;
        container.removeAllViews();
        int size = (int) (56 * getResources().getDisplayMetrics().density);
        int margin = (int) (8 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < AvatarPresets.count(); i++) {
            final String presetUri = AvatarPresets.uriFor(i);
            com.google.android.material.imageview.ShapeableImageView iv =
                    new com.google.android.material.imageview.ShapeableImageView(this);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(margin);
            iv.setLayoutParams(lp);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            iv.setShapeAppearanceModel(
                    iv.getShapeAppearanceModel().toBuilder()
                            .setAllCornerSizes(size / 2f)
                            .build());
            iv.setImageResource(AvatarPresets.drawableAt(i));
            iv.setOnClickListener(v -> selectPreset(presetUri));
            container.addView(iv);
        }
    }

    private void selectPreset(String presetUri) {
        // 选预置头像前，清理之前未保存的临时拍照/相册头像文件
        if (!TextUtils.isEmpty(currentAvatarUri)
                && !AvatarPresets.isPreset(currentAvatarUri)
                && !TextUtils.equals(currentAvatarUri, originalAvatarUri)) {
            repository.deletePhoto(currentAvatarUri);
        }
        currentAvatarUri = presetUri;
        renderAvatar();
    }

    private void updateAvatar(String avatarUri) {
        // 删除上一张未保存的临时头像（预置头像不是文件，跳过）
        if (!TextUtils.isEmpty(currentAvatarUri)
                && !AvatarPresets.isPreset(currentAvatarUri)
                && !TextUtils.equals(currentAvatarUri, originalAvatarUri)
                && !TextUtils.equals(currentAvatarUri, avatarUri)) {
            repository.deletePhoto(currentAvatarUri);
        }
        currentAvatarUri = avatarUri;
        renderAvatar();
    }

    private void removeAvatar() {
        if (!TextUtils.isEmpty(currentAvatarUri)
                && !AvatarPresets.isPreset(currentAvatarUri)
                && !TextUtils.equals(currentAvatarUri, originalAvatarUri)) {
            repository.deletePhoto(currentAvatarUri);
        }
        currentAvatarUri = null;
        renderAvatar();
    }

    private void renderAvatar() {
        if (TextUtils.isEmpty(currentAvatarUri)) {
            binding.ivAvatarPreview.setImageDrawable(null);
            binding.ivAvatarPreview.setVisibility(View.GONE);
            binding.tvAvatarLetter.setVisibility(View.VISIBLE);
            binding.btnAvatarRemove.setVisibility(View.GONE);
        } else if (AvatarPresets.isPreset(currentAvatarUri)) {
            binding.ivAvatarPreview.setVisibility(View.VISIBLE);
            binding.ivAvatarPreview.setImageResource(AvatarPresets.drawableFor(currentAvatarUri));
            binding.tvAvatarLetter.setVisibility(View.GONE);
            binding.btnAvatarRemove.setVisibility(View.VISIBLE);
        } else {
            binding.ivAvatarPreview.setVisibility(View.VISIBLE);
            binding.ivAvatarPreview.setImageURI(Uri.parse(currentAvatarUri));
            binding.tvAvatarLetter.setVisibility(View.GONE);
            binding.btnAvatarRemove.setVisibility(View.VISIBLE);
        }
    }

    private void save() {
        String newUsername = String.valueOf(binding.etProfileUsername.getText()).trim();
        String displayName = String.valueOf(binding.etProfileDisplayName.getText()).trim();
        String motto = String.valueOf(binding.etProfileMotto.getText()).trim();
        String password = String.valueOf(binding.etProfilePassword.getText());
        String confirm = String.valueOf(binding.etProfileConfirmPassword.getText());

        if (newUsername.isEmpty()) {
            Toast.makeText(this, "用户名不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (displayName.isEmpty()) {
            displayName = newUsername;
        }
        if (!password.isEmpty() && !TextUtils.equals(password, confirm)) {
            Toast.makeText(this, "两次输入的密码不一致", Toast.LENGTH_SHORT).show();
            return;
        }

        String error = repository.updateAccount(
                username, newUsername, displayName, motto, currentAvatarUri,
                password.isEmpty() ? null : password);
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
            return;
        }

        // 头像换了：删掉旧头像文件（预置头像不是文件，跳过）
        if (!TextUtils.equals(originalAvatarUri, currentAvatarUri)
                && !TextUtils.isEmpty(originalAvatarUri)
                && !AvatarPresets.isPreset(originalAvatarUri)) {
            repository.deletePhoto(originalAvatarUri);
        }
        username = newUsername;
        originalAvatarUri = currentAvatarUri;
        Toast.makeText(this, "资料已保存", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    @Override
    protected void onDestroy() {
        // 未保存就退出时清理临时头像（预置头像不是文件，跳过）
        if (isFinishing()
                && !TextUtils.isEmpty(currentAvatarUri)
                && !AvatarPresets.isPreset(currentAvatarUri)
                && !TextUtils.equals(currentAvatarUri, originalAvatarUri)) {
            repository.deletePhoto(currentAvatarUri);
        }
        super.onDestroy();
    }
}
