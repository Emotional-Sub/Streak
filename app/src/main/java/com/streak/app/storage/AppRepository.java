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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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

    /**
     * 判断候选密码是否与账号当前密码一致（兼容旧明文账号）。
     */
    private boolean isSamePassword(UserAccount account, String candidate) {
        if (account.isLegacyPlaintext()) {
            return candidate.equals(account.getPassword());
        }
        return PasswordHasher.verify(candidate, account.getSalt(), account.getPasswordHash());
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

    public UserAccount getAccount(String username) {
        for (UserAccount account : loadAccounts()) {
            if (account.getUsername().equals(username)) {
                return account;
            }
        }
        return null;
    }

    public UserAccount getCurrentAccount() {
        return getAccount(getCurrentUser());
    }

    public void updateProfile(String username, String displayName, String motto, String avatarUri) {
        List<UserAccount> accounts = loadAccounts();
        for (UserAccount account : accounts) {
            if (account.getUsername().equals(username)) {
                account.setDisplayName(displayName);
                account.setMotto(motto);
                account.setAvatarUri(avatarUri);
                break;
            }
        }
        saveAccounts(accounts);
    }

    /**
     * 编辑账号：可同时修改用户名（查重）、昵称、签名、头像、密码。
     * newPassword 为空表示不改密码。返回 null 表示成功，否则返回错误信息。
     */
    public String updateAccount(String oldUsername, String newUsername, String displayName,
                                String motto, String avatarUri, String newPassword) {
        if (newUsername == null || newUsername.trim().isEmpty()) {
            return "用户名不能为空";
        }
        newUsername = newUsername.trim();
        List<UserAccount> accounts = loadAccounts();
        UserAccount target = null;
        for (UserAccount account : accounts) {
            if (account.getUsername().equals(oldUsername)) {
                target = account;
            } else if (account.getUsername().equals(newUsername)) {
                return "该用户名已被占用";
            }
        }
        if (target == null) {
            return "账号不存在";
        }

        target.setUsername(newUsername);
        target.setDisplayName(displayName);
        target.setMotto(motto);
        target.setAvatarUri(avatarUri);
        if (newPassword != null && !newPassword.isEmpty()) {
            if (isSamePassword(target, newPassword)) {
                return "新密码不能与原密码相同";
            }
            applyHashedPassword(target, newPassword);
        }
        saveAccounts(accounts);

        // 同步登录态：当前用户名、记住的用户名/密码
        SharedPreferences.Editor editor = preferences.edit();
        if (oldUsername.equals(getCurrentUser())) {
            editor.putString(KEY_CURRENT_USER, newUsername);
        }
        if (oldUsername.equals(getSavedUsername())) {
            editor.putString(KEY_SAVED_USERNAME, newUsername);
            if (newPassword != null && !newPassword.isEmpty()) {
                editor.putString(KEY_SAVED_PASSWORD, newPassword);
            }
        }
        editor.apply();
        return null;
    }

    /**
     * 删除当前账号，并清空全部习惯/打卡/照片/头像（习惯为全局共享数据）。
     */
    public void deleteCurrentAccountAndData() {
        String username = getCurrentUser();
        List<UserAccount> accounts = loadAccounts();
        List<UserAccount> remaining = new ArrayList<>();
        for (UserAccount account : accounts) {
            if (!account.getUsername().equals(username)) {
                remaining.add(account);
            } else {
                deletePhoto(account.getAvatarUri());
            }
        }
        saveAccounts(remaining);

        // 清空习惯、取消提醒、删除所有图片
        for (HabitItem habit : readHabits()) {
            reminderScheduler.cancel(habit.getId());
            deletePhoto(habit.getImageUri());
        }
        writeHabits(new ArrayList<>());
        clearImageDir();

        logout();
        preferences.edit()
                .putString(KEY_SAVED_USERNAME, "")
                .putString(KEY_SAVED_PASSWORD, "")
                .apply();
    }

    private void clearImageDir() {
        File[] files = imageDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /**
     * 把拍照/相册得到的图片复制进头像目录，返回 file:// uri。
     */
    public String copyAvatarImage(Uri uri) {
        try {
            String extension = "jpg";
            String mimeType = context.getContentResolver().getType(uri);
            if (mimeType != null && mimeType.contains("/")) {
                extension = mimeType.substring(mimeType.lastIndexOf('/') + 1);
            }
            File target = new File(imageDir, "avatar_" + System.currentTimeMillis() + "." + extension);
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
        File zipFile = new File(backupDir, "streak_backup_" + timestamp + ".zip");

        List<HabitItem> habits = readHabits();
        List<UserAccount> accounts = loadAccounts();

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            // 1) 习惯 JSON（带导出时间）
            HabitBackup backup = new HabitBackup(
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    habits
            );
            writeZipEntry(zos, "habits.json", gson.toJson(backup).getBytes(StandardCharsets.UTF_8));

            // 2) 账号资料 JSON（含头像引用，不含密码哈希也可，但这里整体备份）
            writeZipEntry(zos, "accounts.json", gson.toJson(accounts).getBytes(StandardCharsets.UTF_8));

            // 3) 所有引用到的图片（习惯照片 + 头像），打包进 images/ 目录，文件名即原名
            for (HabitItem habit : habits) {
                addImageToZip(zos, habit.getImageUri());
            }
            for (UserAccount account : accounts) {
                addImageToZip(zos, account.getAvatarUri());
            }
        } catch (Exception ignored) {
        }
        return zipFile;
    }

    private void writeZipEntry(ZipOutputStream zos, String name, byte[] data) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private void addImageToZip(ZipOutputStream zos, String fileUriOrPath) {
        File source = resolveImageFile(fileUriOrPath);
        if (source == null || !source.exists()) {
            return;
        }
        try {
            zos.putNextEntry(new ZipEntry("images/" + source.getName()));
            try (FileInputStream fis = new FileInputStream(source)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    zos.write(buffer, 0, read);
                }
            }
            zos.closeEntry();
        } catch (Exception ignored) {
        }
    }

    private File resolveImageFile(String fileUriOrPath) {
        if (fileUriOrPath == null || fileUriOrPath.trim().isEmpty()) {
            return null;
        }
        File direct = new File(fileUriOrPath);
        if (direct.exists()) {
            return direct;
        }
        try {
            String path = Uri.parse(fileUriOrPath).getPath();
            if (path != null) {
                return new File(path);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 从 ZIP 备份恢复：还原图片到私有目录，并把 json 中的图片路径重映射到新位置。
     * 返回是否成功。
     */
    public boolean importBackup(Uri zipUri) {
        try (InputStream rawInput = context.getContentResolver().openInputStream(zipUri)) {
            if (rawInput == null) {
                return false;
            }
            // 先把整包读出来，因为要分两遍处理（先解图片再改路径）
            // 为简单起见，单遍解压：图片直接落地，json 暂存
            byte[] habitsJson = null;
            byte[] accountsJson = null;

            try (ZipInputStream zis = new ZipInputStream(rawInput)) {
                ZipEntry entry;
                byte[] buffer = new byte[8192];
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory()) {
                        zis.closeEntry();
                        continue;
                    }
                    if ("habits.json".equals(name)) {
                        habitsJson = readEntryBytes(zis, buffer);
                    } else if ("accounts.json".equals(name)) {
                        accountsJson = readEntryBytes(zis, buffer);
                    } else if (name.startsWith("images/")) {
                        String fileName = name.substring("images/".length());
                        if (!fileName.isEmpty() && !fileName.contains("..")) {
                            File out = new File(imageDir, fileName);
                            try (FileOutputStream fos = new FileOutputStream(out)) {
                                int read;
                                while ((read = zis.read(buffer)) != -1) {
                                    fos.write(buffer, 0, read);
                                }
                            }
                        }
                    }
                    zis.closeEntry();
                }
            }

            if (habitsJson == null) {
                return false;
            }

            // 还原习惯，并把图片路径重映射到本机 imageDir/<原文件名>
            HabitBackup backup = gson.fromJson(
                    new String(habitsJson, StandardCharsets.UTF_8), HabitBackup.class);
            if (backup == null || backup.getHabits() == null) {
                return false;
            }
            List<HabitItem> habits = backup.getHabits();
            for (HabitItem habit : habits) {
                habit.setImageUri(remapImageUri(habit.getImageUri()));
            }
            writeHabits(habits);

            // 还原账号资料中的头像路径（仅更新已存在的同名账号的资料字段）
            if (accountsJson != null) {
                Type type = new TypeToken<List<UserAccount>>() {}.getType();
                List<UserAccount> imported = gson.fromJson(
                        new String(accountsJson, StandardCharsets.UTF_8), type);
                if (imported != null) {
                    List<UserAccount> current = loadAccounts();
                    for (UserAccount importedAccount : imported) {
                        for (UserAccount existing : current) {
                            if (existing.getUsername().equals(importedAccount.getUsername())) {
                                existing.setDisplayName(importedAccount.getDisplayName());
                                existing.setMotto(importedAccount.getMotto());
                                existing.setAvatarUri(remapImageUri(importedAccount.getAvatarUri()));
                                break;
                            }
                        }
                    }
                    saveAccounts(current);
                }
            }

            // 重建提醒
            rescheduleAllReminders();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] readEntryBytes(ZipInputStream zis, byte[] buffer) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        int read;
        while ((read = zis.read(buffer)) != -1) {
            baos.write(buffer, 0, read);
        }
        return baos.toByteArray();
    }

    /**
     * 把备份里的图片路径重映射为本机 imageDir 下的同名文件（若该文件确实已解压存在）。
     */
    private String remapImageUri(String original) {
        File source = resolveImageFile(original);
        if (source == null) {
            return null;
        }
        File local = new File(imageDir, source.getName());
        if (local.exists()) {
            return Uri.fromFile(local).toString();
        }
        return null;
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
