package com.streak.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.streak.app.R;
import com.streak.app.databinding.ActivityRegisterBinding;
import com.streak.app.storage.AppRepository;

public class RegisterActivity extends AppCompatActivity {
    public static final String RESULT_USERNAME = "result_username";

    private ActivityRegisterBinding binding;
    private AppRepository repository;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityRegisterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = new AppRepository(this);

        binding.toolbarRegister.setNavigationOnClickListener(v -> finish());
        binding.btnRegisterSubmit.setOnClickListener(v -> attemptRegister());
    }

    private void attemptRegister() {
        String username = text(binding.etRegisterUsername);
        // 密码不做 trim：trim 会悄悄改掉用户选定的凭据，且与 ProfileEditActivity
        // 改密码处（不 trim）不一致，导致同一串密码在两个入口的登录结果不同
        String password = String.valueOf(binding.etRegisterPassword.getText());
        String confirm = String.valueOf(binding.etRegisterConfirmPassword.getText());

        if (username.isEmpty() || password.isEmpty()) {
            showError(getString(R.string.toast_username_password_empty));
            return;
        }
        if (!TextUtils.equals(password, confirm)) {
            showError(getString(R.string.toast_password_mismatch));
            return;
        }

        String error = repository.registerAccount(username, password);
        if (error != null) {
            showError(error);
            return;
        }

        Toast.makeText(this, R.string.toast_register_success, Toast.LENGTH_SHORT).show();
        Intent result = new Intent().putExtra(RESULT_USERNAME, username);
        setResult(RESULT_OK, result);
        finish();
    }

    private void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String text(android.widget.TextView view) {
        return String.valueOf(view.getText()).trim();
    }
}
