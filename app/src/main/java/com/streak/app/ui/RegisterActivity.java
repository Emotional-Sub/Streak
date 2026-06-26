package com.streak.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

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
        String password = text(binding.etRegisterPassword);
        String confirm = text(binding.etRegisterConfirmPassword);

        if (username.isEmpty() || password.isEmpty()) {
            showError("用户名和密码不能为空");
            return;
        }
        if (!TextUtils.equals(password, confirm)) {
            showError("两次输入的密码不一致");
            return;
        }

        String error = repository.registerAccount(username, password);
        if (error != null) {
            showError(error);
            return;
        }

        Toast.makeText(this, "注册成功，请登录", Toast.LENGTH_SHORT).show();
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
