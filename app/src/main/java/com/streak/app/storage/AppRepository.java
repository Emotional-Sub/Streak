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
import com.streak.app.util.AvatarPresets;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class AppRepository {
    private static final String PREFS_NAME = "java_streak_prefs";
    private static final String KEY_SAVED_USERNAME = "saved_username";
    private static final String KEY_SAVED_PASSWORD = "saved_password";
    private static final String KEY_REMEMBER_PASSWORD = "remember_password";
    private static final String KEY_CURRENT_USER = "current_user";
    private static final String KEY_THEME_MODE = "theme_mode";

    // 主题模式：跟随系统 / 浅色 / 深色
    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

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
            if (!username.equals(account.getUsername())) {
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
        username = username.trim();
        List<UserAccount> accounts = loadAccounts();
        for (UserAccount account : accounts) {
            if (username.equals(account.getUsername())) {
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

    /** 读取主题模式偏好，默认跟随系统。 */
    public int getThemeMode() {
        return preferences.getInt(KEY_THEME_MODE, THEME_SYSTEM);
    }

    public void setThemeMode(int mode) {
        preferences.edit().putInt(KEY_THEME_MODE, mode).apply();
    }

    public UserAccount getAccount(String username) {
        for (UserAccount account : loadAccounts()) {
            if (username.equals(account.getUsername())) {
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
            if (username.equals(account.getUsername())) {
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
            if (oldUsername.equals(account.getUsername())) {
                target = account;
            } else if (newUsername.equals(account.getUsername())) {
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
     * 删除当前账号，并清空全部习惯/打卡及其照片（习惯为全局共享数据）。
     * 只删除本账号头像，保留其它账号的头像文件。
     */
    public void deleteCurrentAccountAndData() {
        String username = getCurrentUser();
        List<UserAccount> accounts = loadAccounts();
        List<UserAccount> remaining = new ArrayList<>();
        for (UserAccount account : accounts) {
            if (!username.equals(account.getUsername())) {
                remaining.add(account);
            } else {
                deletePhoto(account.getAvatarUri());
            }
        }
        saveAccounts(remaining);

        // 清空习惯、取消提醒、删除习惯照片（不动其它账号的头像）
        for (HabitItem habit : readHabits()) {
            reminderScheduler.cancel(habit.getId());
            deletePhoto(habit.getImageUri());
        }
        writeHabits(new ArrayList<>());

        logout();
        preferences.edit()
                .putString(KEY_SAVED_USERNAME, "")
                .putString(KEY_SAVED_PASSWORD, "")
                .apply();
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
            // 文件存在却解析失败：可能是写入中断/损坏。绝不用种子数据覆盖，
            // 而是把损坏文件改名备份后返回空列表，避免用户打卡记录被永久清除。
            backupCorruptFile(habitsFile);
            return new ArrayList<>();
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

            // 2) 账号资料 JSON：含用户名/昵称/签名/头像 + PBKDF2 哈希与盐（绝不含明文密码）。
            //    导出哈希+盐是为了删号后导入能用原密码登回；注意备份文件可被外传，
            //    其中的哈希+盐可用于离线暴力破解，请提醒用户妥善保管备份。
            writeZipEntry(zos, "accounts.json",
                    gson.toJson(sanitizeAccountsForExport(accounts)).getBytes(StandardCharsets.UTF_8));

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

    /**
     * 备份里保留用户名/昵称/签名/头像，以及 PBKDF2 哈希+盐（非明文密码），
     * 以便删号后导入可完整重建账号并用原密码登回。明文 password 字段始终剔除。
     */
    private List<UserAccount> sanitizeAccountsForExport(List<UserAccount> accounts) {
        List<UserAccount> safe = new ArrayList<>();
        for (UserAccount account : accounts) {
            UserAccount copy = new UserAccount();
            copy.setUsername(account.getUsername());
            copy.setDisplayName(account.getDisplayName());
            copy.setMotto(account.getMotto());
            copy.setAvatarUri(account.getAvatarUri());
            // 凭据：仅导出哈希+盐，便于完整恢复；绝不导出明文。
            if (account.isLegacyPlaintext()) {
                // 旧明文账号尚未迁移：导出前临时哈希一份，避免凭据丢失导致导入后无法登录，
                // 同时仍不写出明文。
                String salt = PasswordHasher.generateSalt();
                copy.setSalt(salt);
                copy.setPasswordHash(PasswordHasher.hash(account.getPassword(), salt));
            } else {
                copy.setPasswordHash(account.getPasswordHash());
                copy.setSalt(account.getSalt());
            }
            safe.add(copy);
        }
        return safe;
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
     * 从 ZIP 备份恢复：先把所有条目读入内存并校验 habits.json 可解析，
     * 全部通过后再落地图片、改写习惯/账号，避免「图片已覆盖但 json 解析失败」无法回滚。
     * 返回是否成功。
     */
    public boolean importBackup(Uri zipUri) {
        try (InputStream rawInput = context.getContentResolver().openInputStream(zipUri)) {
            if (rawInput == null) {
                return false;
            }
            byte[] habitsJson = null;
            byte[] accountsJson = null;
            Map<String, byte[]> images = new HashMap<>();

            // 第一遍：全部读入内存，不写磁盘
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
                        if (isSafeImageName(fileName)) {
                            images.put(fileName, readEntryBytes(zis, buffer));
                        }
                    }
                    zis.closeEntry();
                }
            }

            if (habitsJson == null) {
                return false;
            }

            // 校验：habits.json 必须能解析出习惯列表，否则直接放弃，不动任何现有数据
            HabitBackup backup = gson.fromJson(
                    new String(habitsJson, StandardCharsets.UTF_8), HabitBackup.class);
            if (backup == null || backup.getHabits() == null) {
                return false;
            }

            // 校验通过，开始落地图片
            for (Map.Entry<String, byte[]> img : images.entrySet()) {
                File out = new File(imageDir, img.getKey());
                // 规范化路径，确保仍在 imageDir 内（防 Zip Slip）
                if (!out.getCanonicalPath().startsWith(imageDir.getCanonicalPath() + File.separator)) {
                    continue;
                }
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(img.getValue());
                }
            }

            // 还原习惯，并把图片路径重映射到本机 imageDir/<原文件名>
            List<HabitItem> habits = backup.getHabits();
            for (HabitItem habit : habits) {
                habit.setImageUri(remapImageUri(habit.getImageUri()));
            }
            writeHabits(habits);

            // 还原账号：同名账号更新资料+凭据；已删除（不存在）的账号则重建，
            // 使删号后导入能用原用户名+原密码登回。
            if (accountsJson != null) {
                Type type = new TypeToken<List<UserAccount>>() {}.getType();
                List<UserAccount> imported = gson.fromJson(
                        new String(accountsJson, StandardCharsets.UTF_8), type);
                if (imported != null) {
                    List<UserAccount> current = loadAccounts();
                    for (UserAccount importedAccount : imported) {
                        if (importedAccount == null || importedAccount.getUsername() == null) {
                            continue;
                        }
                        UserAccount match = null;
                        for (UserAccount existing : current) {
                            if (importedAccount.getUsername().equals(existing.getUsername())) {
                                match = existing;
                                break;
                            }
                        }
                        if (match != null) {
                            match.setDisplayName(importedAccount.getDisplayName());
                            match.setMotto(importedAccount.getMotto());
                            // 备份缺图时 remap 返回 null，此时保留现有头像而非清空
                            String remappedAvatar = remapImageUri(importedAccount.getAvatarUri());
                            if (remappedAvatar != null) {
                                match.setAvatarUri(remappedAvatar);
                            }
                            // 仅当备份带了凭据时才覆盖，避免把现有密码清空
                            if (importedAccount.getPasswordHash() != null) {
                                match.setPasswordHash(importedAccount.getPasswordHash());
                                match.setSalt(importedAccount.getSalt());
                                match.setPassword(null);
                            }
                        } else {
                            // 重建已删除账号
                            UserAccount restored = new UserAccount();
                            restored.setUsername(importedAccount.getUsername());
                            restored.setDisplayName(importedAccount.getDisplayName());
                            restored.setMotto(importedAccount.getMotto());
                            restored.setAvatarUri(remapImageUri(importedAccount.getAvatarUri()));
                            restored.setPasswordHash(importedAccount.getPasswordHash());
                            restored.setSalt(importedAccount.getSalt());
                            current.add(restored);
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

    private boolean isSafeImageName(String fileName) {
        return fileName != null
                && !fileName.isEmpty()
                && !fileName.contains("..")
                && !fileName.contains("/")
                && !fileName.contains("\\");
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
        // 预置头像是逻辑标识（preset:N），不是文件，原样保留
        if (AvatarPresets.isPreset(original)) {
            return original;
        }
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
                // 单个习惯调度失败不应中断其余（避免一条脏数据让开机后全部提醒丢失）
                try {
                    reminderScheduler.schedule(habit);
                } catch (Exception ignored) {
                }
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

    /**
     * 把二维码 Bitmap 保存到系统相册的 Pictures/Streak 目录。
     * API 29+ 走 MediaStore（免存储权限）；API 26-28 需调用方先拿到 WRITE_EXTERNAL_STORAGE。
     * 返回保存后的图片 Uri；失败返回 null。注意：应在后台线程调用，避免阻塞主线程。
     */
    public Uri saveQrToGallery(android.graphics.Bitmap bitmap, String displayName) {
        if (bitmap == null) {
            return null;
        }
        String fileName = sanitizeFileName(displayName) + "_" + System.currentTimeMillis() + ".png";
        android.content.ContentResolver resolver = context.getContentResolver();
        android.content.ContentValues values = new android.content.ContentValues();
        values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/Streak");
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return null;
            }
            try (java.io.OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    resolver.delete(uri, null, null);
                    return null;
                }
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception e) {
                resolver.delete(uri, null, null);
                return null;
            }
            values.clear();
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri;
        } else {
            // API 26-28：写入公共 Pictures/Streak 目录，再插入 MediaStore 索引让相册可见
            File picturesDir = new File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_PICTURES), "Streak");
            //noinspection ResultOfMethodCallIgnored
            picturesDir.mkdirs();
            File target = new File(picturesDir, fileName);
            try (FileOutputStream out = new FileOutputStream(target)) {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception e) {
                return null;
            }
            values.put(android.provider.MediaStore.Images.Media.DATA, target.getAbsolutePath());
            return resolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        }
    }

    /**
     * 把 Bitmap 写入 cache/shares 目录并返回可对外分享的 FileProvider content:// uri。
     * 用于成就战报等临时图片分享，失败返回 null。应在后台线程调用。
     */
    public Uri cacheBitmapForShare(android.graphics.Bitmap bitmap, String baseName) {
        if (bitmap == null) {
            return null;
        }
        try {
            File shareDir = new File(context.getCacheDir(), "shares");
            //noinspection ResultOfMethodCallIgnored
            shareDir.mkdirs();
            File target = new File(shareDir,
                    sanitizeFileName(baseName) + "_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream out = new FileOutputStream(target)) {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
            return FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", target);
        } catch (Exception e) {
            return null;
        }
    }

    private String sanitizeFileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "streak_qr";
        }
        // 去掉文件名里的非法字符，避免拼路径出错
        String cleaned = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return cleaned.isEmpty() ? "streak_qr" : cleaned;
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
            // 文件存在却解析失败：先把损坏文件改名备份，绝不用默认账号覆盖，
            // 否则用户全部账号会被永久清除并替换为 student/123456。
            backupCorruptFile(accountsFile);
            return new ArrayList<>();
        }
    }

    /**
     * 把解析失败的损坏文件改名为 <名>.corrupt-<时间戳>，保留现场以便排查/手动恢复，
     * 同时让后续逻辑认为「文件不存在」从而走正常的默认初始化路径。
     */
    private void backupCorruptFile(File file) {
        try {
            if (file.exists()) {
                File backup = new File(file.getParentFile(),
                        file.getName() + ".corrupt-" + System.currentTimeMillis());
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(backup);
            }
        } catch (Exception ignored) {
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
