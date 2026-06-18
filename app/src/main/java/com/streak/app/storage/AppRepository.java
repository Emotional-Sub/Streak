package com.streak.app.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.streak.app.model.CameraCaptureInfo;
import com.streak.app.model.HabitBackup;
import com.streak.app.model.HabitItem;
import com.streak.app.model.UserAccount;
import com.streak.app.reminder.ReminderScheduler;
import com.streak.app.util.PasswordHasher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AppRepository {
    private static final String PREFS_NAME = "java_streak_prefs";
    private static final String KEY_SAVED_USERNAME = "saved_username";
    private static final String KEY_SAVED_PASSWORD = "saved_password";
    private static final String KEY_REMEMBER_PASSWORD = "remember_password";
    private static final String KEY_CURRENT_USER = "current_user";

    private final Context context;
    private final SharedPreferences preferences;
    private final Gson gson = new Gson();
    private final File habitsFile;
    private final File accountsFile;
    private final File imageDir;
    private final File backupDir;
    private final ReminderScheduler reminderScheduler;

    public AppRepository(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.habitsFile = new File(this.context.getFilesDir(), "habits.json");
        this.accountsFile = new File(this.context.getFilesDir(), "accounts.json");
        this.imageDir = new File(this.context.getFilesDir(), "habit_images");
        this.backupDir = new File(this.context.getFilesDir(), "exports");
        this.imageDir.mkdirs();
        this.backupDir.mkdirs();
        this.reminderScheduler = new ReminderScheduler(this.context);
    }

    public boolean validateLogin(String username, String password) {
        List<UserAccount> accounts = loadAccounts();
        boolean migrated = false;
        boolean matched = false;
        for (UserAccount account : accounts) {
            if (!account.getUsername().equals(username)) {
                continue;
            }
            if (account.isLegacyPlaintext()) {
                // 旧明文账号：明文比对成功后立即升级为 PBKDF2 哈希。
                if (account.getPassword().equals(password)) {
                    applyHashedPassword(account, password);
                    migrated = true;
                    matched = true;
                }
            } else if (PasswordHasher.verify(password, account.getSalt(), account.getPasswordHash())) {
                matched = true;
            }
            break;
        }
        if (migrated) {
            saveAccounts(accounts);
        }
        return matched;
    }

    public String registerAccount(String username, String password) {
        if (username.trim().isEmpty() || password.trim().isEmpty()) {
            return "用户名和密码不能为空";
        }
        List<UserAccount> accounts = loadAccounts();
        for (UserAccount account : accounts) {
            if (account.getUsername().equals(username)) {
                return "该用户名已存在";
            }
        }
        UserAccount account = new UserAccount();
        account.setUsername(username);
        applyHashedPassword(account, password);
        accounts.add(account);
        saveAccounts(accounts);
        return null;
    }

    private void applyHashedPassword(UserAccount account, String password) {
        String salt = PasswordHasher.generateSalt();
        account.setSalt(salt);
        account.setPasswordHash(PasswordHasher.hash(password, salt));
        account.setPassword(null);
    }

    public void saveLoginState(String username, String password, boolean rememberPassword, String currentUser) {
        preferences.edit()
                .putBoolean(KEY_REMEMBER_PASSWORD, rememberPassword)
                .putString(KEY_CURRENT_USER, currentUser)
                .putString(KEY_SAVED_USERNAME, rememberPassword ? username : "")
                .putString(KEY_SAVED_PASSWORD, rememberPassword ? password : "")
                .apply();
    }

    public void logout() {
        preferences.edit().putString(KEY_CURRENT_USER, "").apply();
    }

    public String getSavedUsername() {
        return preferences.getString(KEY_SAVED_USERNAME, "");
    }

    public String getSavedPassword() {
        return preferences.getString(KEY_SAVED_PASSWORD, "");
    }

    public boolean isRememberPassword() {
        return preferences.getBoolean(KEY_REMEMBER_PASSWORD, true);
    }

    public String getCurrentUser() {
        return preferences.getString(KEY_CURRENT_USER, "");
    }

    public List<HabitItem> readHabits() {
        if (!habitsFile.exists()) {
            List<HabitItem> seed = seedHabits();
            writeHabits(seed);
            return seed;
        }
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(habitsFile), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<List<HabitItem>>() {}.getType();
            List<HabitItem> habits = gson.fromJson(reader, type);
            if (habits == null) {
                habits = seedHabits();
                writeHabits(habits);
            }
            return habits;
        } catch (Exception e) {
            List<HabitItem> seed = seedHabits();
            writeHabits(seed);
            return seed;
        }
    }

    public void writeHabits(List<HabitItem> habits) {
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(habitsFile, false), StandardCharsets.UTF_8)) {
            gson.toJson(habits, writer);
        } catch (Exception ignored) {
        }
    }

    public HabitItem findHabitById(long habitId) {
        for (HabitItem item : readHabits()) {
            if (item.getId() == habitId) {
                return item;
            }
        }
        return null;
    }

    public File exportBackup() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File file = new File(backupDir, "habit_backup_" + timestamp + ".json");
        HabitBackup backup = new HabitBackup(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                readHabits()
        );
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
            gson.toJson(backup, writer);
        } catch (Exception ignored) {
        }
        return file;
    }

    public void syncReminder(HabitItem habit) {
        reminderScheduler.schedule(habit);
    }

    /**
     * 重新调度所有开启提醒的习惯，用于开机后恢复闹钟。
     */
    public void rescheduleAllReminders() {
        for (HabitItem habit : readHabits()) {
            if (habit.isReminderEnabled()) {
                reminderScheduler.schedule(habit);
            }
        }
    }

    public void cancelReminder(long habitId) {
        reminderScheduler.cancel(habitId);
    }

    public CameraCaptureInfo createCameraCapture() {
        File file = new File(imageDir, "camera_" + System.currentTimeMillis() + ".jpg");
        Uri uri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                file
        );
        return new CameraCaptureInfo(uri, file.getAbsolutePath());
    }

    public String persistCapturedPhoto(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            return null;
        }
        return Uri.fromFile(file).toString();
    }

    public void deletePhoto(String filePathOrUri) {
        if (filePathOrUri == null || filePathOrUri.trim().isEmpty()) {
            return;
        }
        try {
            File directFile = new File(filePathOrUri);
            File target = directFile.exists() ? directFile : new File(Uri.parse(filePathOrUri).getPath());
            if (target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
        } catch (Exception ignored) {
        }
    }

    public String copyGalleryImage(Uri uri) {
        try {
            String extension = "jpg";
            String mimeType = context.getContentResolver().getType(uri);
            if (mimeType != null && mimeType.contains("/")) {
                extension = mimeType.substring(mimeType.lastIndexOf('/') + 1);
            }
            File target = new File(imageDir, "gallery_" + System.currentTimeMillis() + "." + extension);
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(target)) {
                if (input == null) {
                    return null;
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
            return Uri.fromFile(target).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private List<UserAccount> loadAccounts() {
        if (!accountsFile.exists()) {
            List<UserAccount> defaults = defaultAccounts();
            saveAccounts(defaults);
            return defaults;
        }
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(accountsFile), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<List<UserAccount>>() {}.getType();
            List<UserAccount> accounts = gson.fromJson(reader, type);
            return accounts == null ? new ArrayList<>() : accounts;
        } catch (Exception e) {
            List<UserAccount> defaults = defaultAccounts();
            saveAccounts(defaults);
            return defaults;
        }
    }

    private List<UserAccount> defaultAccounts() {
        UserAccount student = new UserAccount();
        student.setUsername("student");
        applyHashedPassword(student, "123456");
        List<UserAccount> defaults = new ArrayList<>();
        defaults.add(student);
        return defaults;
    }

    private void saveAccounts(List<UserAccount> accounts) {
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(accountsFile, false), StandardCharsets.UTF_8)) {
            gson.toJson(accounts, writer);
        } catch (Exception ignored) {
        }
    }

    private List<HabitItem> seedHabits() {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        List<HabitItem> seed = new ArrayList<>();
        seed.add(new HabitItem(
                System.currentTimeMillis(),
                "晨跑打卡",
                "坚持晨跑 30 分钟，结束后做 5 分钟拉伸。",
                "06:30",
                now,
                null,
                "运动",
                Arrays.asList("晨练", "有氧"),
                new ArrayList<>(),
                true
        ));
        seed.add(new HabitItem(
                System.currentTimeMillis() + 1,
                "英语单词复习",
                "复习 25 个英语单词，并拍照记录学习笔记。",
                "21:00",
                now,
                null,
                "学习",
                Arrays.asList("英语", "单词"),
                new ArrayList<>(),
                true
        ));
        return seed;
    }

}
