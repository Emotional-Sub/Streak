package com.streak.app.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.streak.app.db.CheckInRecordDao;
import com.streak.app.db.HabitDao;
import com.streak.app.db.StreakDatabase;
import com.streak.app.db.UserDao;
import com.streak.app.model.BackupEnvelope;
import com.streak.app.model.CameraCaptureInfo;
import com.streak.app.model.CheckInRecord;
import com.streak.app.model.HabitBackup;
import com.streak.app.model.HabitItem;
import com.streak.app.model.UserAccount;
import com.streak.app.reminder.ReminderScheduler;
import com.streak.app.util.AvatarPresets;
import com.streak.app.util.PasswordHasher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
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
    // 历史遗留键：曾明文存储密码，现已停用。仅保留常量用于启动时清除旧值。
    private static final String KEY_LEGACY_SAVED_PASSWORD = "saved_password";
    private static final String KEY_REMEMBER_PASSWORD = "remember_password";
    private static final String KEY_CURRENT_USER = "current_user";
    private static final String KEY_THEME_MODE = "theme_mode";

    // 主题模式：跟随系统 / 浅色 / 深色
    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    // 存储初始化标记：JSON->Room 迁移 + 首启补种子只做一次
    private static final String KEY_STORAGE_INITIALIZED = "storage_initialized";

    // ZIP 导入安全上限：个人打卡备份体量很小，用保守阈值挡住恶意/超大 ZIP 撑爆内存。
    // 单条目 16MB（覆盖高清照片有余）、总解压 128MB、最多 500 个条目。
    private static final long MAX_ENTRY_BYTES = 16L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    private static final int MAX_ENTRY_COUNT = 500;

    private final Context context;
    private final SharedPreferences preferences;
    private final Gson gson = new Gson();
    // 旧 JSON 文件仅用于首启迁移与损坏文件归档；日常读写走 Room。
    private final File habitsFile;
    private final File accountsFile;
    private final File imageDir;
    private final File backupDir;
    private final ReminderScheduler reminderScheduler;
    private final StreakDatabase database;
    private final HabitDao habitDao;
    private final UserDao userDao;
    private final CheckInRecordDao checkInRecordDao;

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
        this.database = StreakDatabase.getInstance(this.context);
        this.habitDao = database.habitDao();
        this.userDao = database.userDao();
        this.checkInRecordDao = database.checkInRecordDao();
        purgeLegacyPlaintextPassword();
        initializeStorageIfNeeded();
    }

    /**
     * 清除历史版本明文存储在 SharedPreferences 里的密码（安全整改）。
     * 现在只记住用户名，不再回填/持久化任何密码。
     */
    private void purgeLegacyPlaintextPassword() {
        if (preferences.contains(KEY_LEGACY_SAVED_PASSWORD)) {
            preferences.edit().remove(KEY_LEGACY_SAVED_PASSWORD).apply();
        }
    }

    /**
     * 存储首启初始化，只执行一次（幂等）。统一在此决策「迁移旧数据 vs 补种子」，
     * 避免把补种子判据散落到 read 方法里被「表为空」被动触发——那样会让老用户
     * 「刻意清零」的状态被误当成全新安装而凭空塞回种子习惯/默认账号。
     *
     * 规则：
     * - 存在旧 JSON 文件（老用户升级）：迁移其内容进 Room（哪怕是空列表也照迁，
     *   即尊重「用户曾删光」的真实状态，不补种子），迁移后归档旧文件。
     * - 不存在旧 JSON 文件（全新安装）：写入种子习惯 + 默认演示账号 student/123456。
     *
     * 幂等保证：每步写入前用 count()==0 守卫，即便标记因进程被杀未落盘、下次重跑，
     * 也不会把数据重复追加。全部成功后才置位标记。
     */
    private void initializeStorageIfNeeded() {
        if (preferences.getBoolean(KEY_STORAGE_INITIALIZED, false)) {
            return;
        }
        try {
            boolean hadLegacyAccounts = accountsFile.exists();
            boolean hadLegacyHabits = habitsFile.exists();

            // 账号：老用户迁移旧文件；全新安装补默认账号。均以空表为前提防重复。
            if (hadLegacyAccounts) {
                List<UserAccount> legacyAccounts = readLegacyAccountsFromJson();
                if (userDao.count() == 0 && !legacyAccounts.isEmpty()) {
                    userDao.upsertAll(legacyAccounts);
                }
                archiveLegacyFile(accountsFile);
            } else if (userDao.count() == 0) {
                userDao.upsertAll(defaultAccounts());
            }

            // 习惯：老用户迁移旧文件（空列表也尊重，不补种子）；全新安装补种子。
            if (hadLegacyHabits) {
                List<HabitItem> legacyHabits = readLegacyHabitsFromJson();
                // 旧 JSON 时代习惯是全局共享、无归属的，统一归给演示账号 student，
                // 与 Room v1->v2 迁移及种子习惯的归属保持一致。
                for (HabitItem habit : legacyHabits) {
                    if (habit.getOwnerUsername() == null || habit.getOwnerUsername().isEmpty()) {
                        habit.setOwnerUsername("student");
                    }
                }
                if (habitDao.count() == 0 && !legacyHabits.isEmpty()) {
                    habitDao.upsertAll(legacyHabits);
                    // 这些习惯归属 student，但旧 accounts.json 里未必有 student 账号
                    //（用户可能改过用户名或删过 student）。若缺失，补一个默认演示账号，
                    // 否则这些习惯会成为「登不进去的账号」名下的孤儿数据，用户永远看不到。
                    ensureAccountExists("student");
                }
                archiveLegacyFile(habitsFile);
            } else if (habitDao.count() == 0) {
                habitDao.upsertAll(seedHabits());
            }
        } catch (Exception ignored) {
            // 初始化失败不阻断启动；标记不置位，下次重试（count 守卫保证不会重复）。
            return;
        }
        preferences.edit().putBoolean(KEY_STORAGE_INITIALIZED, true).apply();
    }

    /** 读旧 habits.json（迁移专用），解析失败返回空列表，绝不覆盖。 */
    private List<HabitItem> readLegacyHabitsFromJson() {
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(habitsFile), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<List<HabitItem>>() {}.getType();
            List<HabitItem> habits = gson.fromJson(reader, type);
            return habits == null ? new ArrayList<>() : habits;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 读旧 accounts.json（迁移专用），解析失败返回空列表。 */
    private List<UserAccount> readLegacyAccountsFromJson() {
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(accountsFile), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<List<UserAccount>>() {}.getType();
            List<UserAccount> accounts = gson.fromJson(reader, type);
            return accounts == null ? new ArrayList<>() : accounts;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 迁移后把旧 JSON 文件改名归档，防止重复迁移。 */
    private void archiveLegacyFile(File file) {
        try {
            if (file.exists()) {
                File archived = new File(file.getParentFile(), file.getName() + ".migrated");
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(archived);
            }
        } catch (Exception ignored) {
        }
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

    /**
     * 保存登录态。安全整改：只在「记住用户名」勾选时保存用户名，绝不再持久化密码。
     * 保留 password 形参是为了兼容调用方签名，但不写入任何存储。
     */
    public void saveLoginState(String username, String password, boolean rememberPassword, String currentUser) {
        preferences.edit()
                .putBoolean(KEY_REMEMBER_PASSWORD, rememberPassword)
                .putString(KEY_CURRENT_USER, currentUser)
                .putString(KEY_SAVED_USERNAME, rememberPassword ? username : "")
                .apply();
    }

    public void logout() {
        // 退出前取消本账号所有习惯的提醒：否则退出后旧账号的闹钟仍会按天续排、
        // 通知照常弹出，甚至在换登其它账号后造成串扰。必须在清空 current_user 之前
        // 读取，readHabits() 依赖 getCurrentUser() 定位当前账号的习惯。
        cancelRemindersForCurrentUser();
        preferences.edit().putString(KEY_CURRENT_USER, "").apply();
    }

    /** 取消当前登录账号名下所有习惯的提醒闹钟（退出/切换账号时用）。 */
    private void cancelRemindersForCurrentUser() {
        for (HabitItem habit : readHabits()) {
            reminderScheduler.cancel(habit.getId());
        }
    }

    public String getSavedUsername() {
        return preferences.getString(KEY_SAVED_USERNAME, "");
    }

    /** 是否记住用户名（复选框语义已从「记住密码」收敛为「记住用户名」）。 */
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
        // 改名后必须把该账号名下所有习惯的归属同步到新用户名，
        // 否则 readHabits() 走 getByOwner(新名) 会查不到旧习惯，用户会以为数据全没了。
        // 原子性：习惯改归属与账号表替换必须绑成一个事务——否则若「习惯已改到新名、
        // 账号表却写入失败」，这些习惯会归给一个尚不存在的用户名，成为查不出的孤儿数据。
        final String finalNewUsername = newUsername;
        final boolean renamed = !oldUsername.equals(finalNewUsername);
        final List<UserAccount> toSave = accounts;
        database.runInTransaction(() -> {
            if (renamed) {
                habitDao.updateOwner(oldUsername, finalNewUsername);
            }
            saveAccounts(toSave);
        });

        // 同步登录态：当前用户名、记住的用户名（不再持久化任何密码）
        SharedPreferences.Editor editor = preferences.edit();
        if (oldUsername.equals(getCurrentUser())) {
            editor.putString(KEY_CURRENT_USER, newUsername);
        }
        if (oldUsername.equals(getSavedUsername())) {
            editor.putString(KEY_SAVED_USERNAME, newUsername);
        }
        editor.apply();
        return null;
    }

    /**
     * 删除当前账号，并只清空「本账号」的习惯/打卡及其照片（数据隔离后不再动其它账号）。
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

        // 先趁登录态仍在，清理本账号习惯：取消提醒、删照片，再删本账号的习惯行。
        // 注意顺序——readHabits 依赖 getCurrentUser()，必须在 logout/清账号前取。
        List<HabitItem> ownHabits = readHabits();
        for (HabitItem habit : ownHabits) {
            reminderScheduler.cancel(habit.getId());
            deletePhoto(habit.getImageUri());
            // 删除本账号各习惯打卡记录里附带的照片（与习惯自身 imageUri 独立）
            deleteCheckInPhotos(habit.getId());
        }
        if (username != null && !username.isEmpty()) {
            // 打卡记录靠 habits 子查询定位归属，必须在删 habits 行之前先删记录。
            // 事务保证「删记录」与「删习惯」一并成败，不留孤儿打卡记录。
            final String owner = username;
            database.runInTransaction(() -> {
                checkInRecordDao.deleteByOwner(owner);
                habitDao.clearByOwner(owner);
            });
        }

        saveAccounts(remaining);

        logout();
        preferences.edit()
                .putString(KEY_SAVED_USERNAME, "")
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
        // 数据隔离：只返回当前登录账号的习惯。种子/迁移已在 initializeStorageIfNeeded()
        // 首启统一处理，这里纯读取。不再「表空补种子」——否则用户删光习惯后重进会凭空冒出种子。
        String owner = getCurrentUser();
        if (owner == null || owner.isEmpty()) {
            return new ArrayList<>();
        }
        List<HabitItem> habits = habitDao.getByOwner(owner);
        if (habits == null) {
            return new ArrayList<>();
        }
        // 打卡真相源是 check_in_records 表；habits 行已不含 completedDates/notes 两列。
        // 读出习惯后把各自的打卡记录聚合回填进内存派生字段，供既有约 90 处消费端零改动使用。
        // 消除 N+1：一次批量取回这批习惯的全部记录，在内存按 habitId 分组回填，
        // 而非对每个习惯各查一次 getByHabit。
        List<Long> ids = new ArrayList<>(habits.size());
        for (HabitItem habit : habits) {
            ids.add(habit.getId());
        }
        Map<Long, List<CheckInRecord>> byHabit = recordsGroupedByHabit(ids);
        for (HabitItem habit : habits) {
            aggregateCheckInsInto(habit, byHabit.get(habit.getId()));
        }
        return habits;
    }

    /**
     * 批量取回给定习惯的打卡记录并按 habitId 分组（消除 N+1）。空列表直接返回空 map，
     * 避免拼出 {@code IN ()} 空集查询。
     */
    private Map<Long, List<CheckInRecord>> recordsGroupedByHabit(List<Long> habitIds) {
        Map<Long, List<CheckInRecord>> byHabit = new HashMap<>();
        if (habitIds == null || habitIds.isEmpty()) {
            return byHabit;
        }
        List<CheckInRecord> records = checkInRecordDao.getByHabits(habitIds);
        if (records != null) {
            for (CheckInRecord record : records) {
                byHabit.computeIfAbsent(record.getHabitId(), k -> new ArrayList<>()).add(record);
            }
        }
        return byHabit;
    }

    /**
     * 把某习惯在 check_in_records 表里的打卡记录聚合回填进它的内存派生字段
     * （completedDates / notes），使既有统计/展示代码无需改动即可继续读。
     * 记录表是唯一真相源，此处只是「读时物化」出旧的两个视图字段。
     */
    private void aggregateCheckInsInto(HabitItem habit) {
        if (habit == null) {
            return;
        }
        aggregateCheckInsInto(habit, checkInRecordDao.getByHabit(habit.getId()));
    }

    /**
     * 用「已取好的记录列表」把打卡数据聚合回填进内存派生字段，避免重复查库。
     * readHabits 批量取记录后按 habitId 分组，对每个习惯调用此重载（消除 N+1）。
     */
    private void aggregateCheckInsInto(HabitItem habit, List<CheckInRecord> records) {
        if (habit == null) {
            return;
        }
        List<String> dates = new ArrayList<>();
        java.util.Map<String, String> notes = new java.util.HashMap<>();
        if (records != null) {
            for (CheckInRecord record : records) {
                String date = record.getDate();
                if (date == null || date.isEmpty()) {
                    continue;
                }
                dates.add(date);
                String note = record.getNote();
                if (note != null && !note.trim().isEmpty()) {
                    notes.put(date, note);
                }
            }
        }
        habit.setCompletedDates(dates);
        habit.setNotes(notes);
    }

    public void writeHabits(List<HabitItem> habits) {
        // 整体替换「当前账号」的习惯：清空该账号旧数据后批量写入，事务保证一致性，
        // 且绝不触碰其它账号的习惯。写入前给每条盖上归属，避免导入/构造的数据漏了 owner。
        String owner = getCurrentUser();
        if (owner == null || owner.isEmpty()) {
            return;
        }
        List<HabitItem> scoped = habits == null ? new ArrayList<>() : habits;
        for (HabitItem habit : scoped) {
            if (habit.getOwnerUsername() == null || habit.getOwnerUsername().isEmpty()) {
                habit.setOwnerUsername(owner);
            }
        }
        // 习惯行与打卡记录在同一事务里替换：先整表替换该账号的习惯，
        // 再按每个习惯的内存派生字段（completedDates/notes）同步其打卡记录，
        // 保证「习惯已换、记录未换」的半成品状态不会出现。
        database.runInTransaction(() -> {
            habitDao.replaceAllForOwner(owner, scoped);
            for (HabitItem habit : scoped) {
                syncCheckInsFrom(habit);
            }
        });
    }

    public HabitItem findHabitById(long habitId) {
        HabitItem habit = habitDao.findById(habitId);
        // 同 readHabits：把打卡记录聚合回填进内存派生字段，保证按 id 读出的习惯也带打卡数据。
        aggregateCheckInsInto(habit);
        return habit;
    }

    /** 取某习惯某天的打卡记录（含心情/耗时/照片），无则 null。供新打卡 UI/详情页读富字段。 */
    public CheckInRecord getCheckIn(long habitId, String date) {
        if (date == null || date.isEmpty()) {
            return null;
        }
        return checkInRecordDao.getByHabitAndDate(habitId, date);
    }

    /**
     * 取某习惯全部打卡记录，按日期降序（最近在前）。供详情页时间线直接读富字段
     * （心情/耗时/照片），不经 completedDates/notes 过渡视图。
     */
    public List<CheckInRecord> getCheckIns(long habitId) {
        List<CheckInRecord> records = checkInRecordDao.getByHabit(habitId);
        if (records == null) {
            return new ArrayList<>();
        }
        java.util.Collections.sort(records, (a, b) -> b.getDate().compareTo(a.getDate()));
        return records;
    }

    /**
     * 直接写入/覆盖某习惯某天的打卡记录（含心情/耗时/照片/备注）——打卡真相源的直写入口。
     *
     * <p><b>为什么不走 completedDates/notes 派生字段。</b>那两个字段是过渡兼容视图，只能表达
     * 「哪天打过卡」和「当天备注」，无法携带心情/耗时/照片。新打卡 UI 直接调本方法把富信息落到
     * {@link CheckInRecord} 表（唯一真相源），(habitId,date) 冲突时 {@code @Upsert} 原地更新当天那条。</p>
     *
     * <p>换照片时删除被替换掉的旧照片文件，避免磁盘留孤儿图。</p>
     */
    public void upsertCheckIn(long habitId, String date, int mood,
                             int durationMinutes, String note, String photoUri) {
        if (date == null || date.isEmpty()) {
            return;
        }
        CheckInRecord existing = checkInRecordDao.getByHabitAndDate(habitId, date);
        // 照片换了：先记下旧照片路径，成功写入后再删，避免写失败却已删旧图。
        String oldPhoto = existing == null ? null : existing.getPhotoUri();

        CheckInRecord record = existing == null ? new CheckInRecord() : existing;
        record.setHabitId(habitId);
        record.setDate(date);
        record.setMood(mood);
        record.setDurationMinutes(durationMinutes);
        record.setNote(note == null || note.trim().isEmpty() ? null : note.trim());
        record.setPhotoUri(photoUri == null || photoUri.trim().isEmpty() ? null : photoUri.trim());
        checkInRecordDao.upsert(record);

        if (oldPhoto != null && !oldPhoto.equals(record.getPhotoUri())) {
            deletePhoto(oldPhoto);
        }
    }

    /**
     * 撤销某习惯某天的打卡：删记录表里的那一条，并删掉其附带的打卡照片文件（避免孤儿图）。
     * 直写真相源，不经派生字段。
     */
    public void removeCheckIn(long habitId, String date) {
        if (date == null || date.isEmpty()) {
            return;
        }
        CheckInRecord existing = checkInRecordDao.getByHabitAndDate(habitId, date);
        if (existing != null) {
            deletePhoto(existing.getPhotoUri());
        }
        checkInRecordDao.deleteByHabitAndDate(habitId, date);
    }

    /**
     * 生成全表唯一的习惯 id（以当前毫秒为基准，占用则递增）。
     * id 是全表主键，必须对整表防撞——不能只在当前账号内查，否则两个账号在同一毫秒
     * 新建可能撞 id，upsert 的 REPLACE 会跨账号覆盖，破坏数据隔离。
     */
    public long generateUniqueHabitId() {
        long id = System.currentTimeMillis();
        while (habitDao.existsById(id)) {
            id++;
        }
        return id;
    }

    /**
     * 保存单个习惯（新增或更新，按 id 主键 upsert）。
     * 相比整表 writeHabits，只动这一行，避免「读全量→改一条→写全量」在并发下
     * 用过期快照覆盖掉其它习惯的改动（补卡、编辑、提醒回执可能同时发生）。
     * 新习惯若未设归属，则盖上当前登录账号，保证数据隔离。
     */
    public void saveHabit(HabitItem habit) {
        if (habit != null) {
            if (habit.getOwnerUsername() == null || habit.getOwnerUsername().isEmpty()) {
                habit.setOwnerUsername(getCurrentUser());
            }
            // 习惯行 upsert 与其打卡记录同步绑成一个事务：既有打卡 UI 走「读改写整条习惯」
            // （toggleDateCheckIn/writeTodayCheckIn 改内存的 completedDates/notes 后调本方法），
            // 这里据此把记录表同步到与派生字段一致，让旧写入路径零改动即落到规范化表。
            database.runInTransaction(() -> {
                habitDao.upsert(habit);
                syncCheckInsFrom(habit);
            });
        }
    }

    /**
     * 把某习惯内存派生字段（completedDates/notes）的状态同步进 check_in_records 表：
     * 新增缺失日期的记录、删除已不在 completedDates 里的记录、更新备注。
     *
     * <p>关键：对仍保留的日期，<b>保留其已有的 mood/duration/photo</b>——既有打卡 UI 只改
     * 日期与备注，若这里整表重建会把心情/耗时/照片抹掉。故按日期 diff 增量同步，
     * 而非 clear + 重插。记录表是真相源，本方法是「旧视图字段 -> 真相源」的回写桥。</p>
     */
    private void syncCheckInsFrom(HabitItem habit) {
        if (habit == null) {
            return;
        }
        long habitId = habit.getId();
        List<CheckInRecord> existing = checkInRecordDao.getByHabit(habitId);
        java.util.Map<String, CheckInRecord> existingByDate = new java.util.HashMap<>();
        if (existing != null) {
            for (CheckInRecord record : existing) {
                existingByDate.put(record.getDate(), record);
            }
        }

        java.util.Set<String> targetDates = new java.util.LinkedHashSet<>();
        List<String> completed = habit.getCompletedDates();
        if (completed != null) {
            for (String date : completed) {
                if (date != null && !date.isEmpty()) {
                    targetDates.add(date);
                }
            }
        }

        // 删除：已有记录但目标日期集合里没有的 -> 撤销打卡
        for (String date : existingByDate.keySet()) {
            if (!targetDates.contains(date)) {
                checkInRecordDao.deleteByHabitAndDate(habitId, date);
            }
        }

        // 新增/更新：对每个目标日期，保留旧记录的 mood/duration/photo，只覆盖 note。
        for (String date : targetDates) {
            String note = habit.getNote(date);
            CheckInRecord record = existingByDate.get(date);
            if (record == null) {
                record = new CheckInRecord();
                record.setHabitId(habitId);
                record.setDate(date);
            }
            record.setNote(note == null || note.isEmpty() ? null : note);
            checkInRecordDao.upsert(record);
        }
    }

    /**
     * 按 id 删除单个习惯，并限定归属为当前账号。同样只动一行，不整表覆盖，避免并发丢更新。
     * 带 owner 过滤是数据隔离的防御措施：即便传入其它账号的 id 也删不到，不会跨账号误删。
     */
    public void deleteHabitById(long habitId) {
        String owner = getCurrentUser();
        if (owner == null || owner.isEmpty()) {
            return;
        }
        // v4 起 check_in_records 有 FK ON DELETE CASCADE，删习惯行即自动删其打卡记录。
        // 但先删磁盘上的打卡照片文件：DB 级联只清行、不清文件，行删掉后就再也枚举不到
        // 这些照片路径了，会留下孤儿图。故先读照片路径删文件，再删习惯（触发级联删记录）。
        // 事务包住删习惯这步；照片删除是文件系统操作，放事务外（失败也不影响 DB 一致性）。
        HabitItem target = habitDao.findById(habitId);
        if (target == null || !owner.equals(target.getOwnerUsername())) {
            return; // 不存在或非本账号：不删（数据隔离防御）
        }
        deleteCheckInPhotos(habitId);
        database.runInTransaction(() -> habitDao.deleteByIdForOwner(habitId, owner));
    }

    /**
     * 导出整机全量备份（ZIP）。
     *
     * <p><b>设计取舍：整机全量、非账号隔离。</b>备份刻意导出「本机所有账号」的习惯与资料，
     * 而非仅当前登录账号。理由：① 导入可在登录页（未登录、无当前账号）触发，是本 App
     * 「离线优先、无后端」的跨设备/重装迁移方案，未登录态下没有「当前账号」可界定范围；
     * ② 删号后仍能用同一份备份把账号连凭据一起恢复并用原密码登回。</p>
     *
     * <p><b>安全权衡（答辩要点）：</b>正因为是整机全量，备份文件本身即包含所有账号的
     * PBKDF2 哈希+盐（<b>绝不含明文密码</b>）。这带来两个已知且刻意接受的性质：</p>
     * <ul>
     *   <li>持有备份文件者可离线对哈希做暴力/字典破解——故 UI 层应提示用户妥善保管备份，
     *       且 App 已设 allowBackup=false + 自定义 dataExtractionRules 阻止系统级备份外泄凭据。</li>
     *   <li>导入侧做了防接管处理：{@link #importBackup} 对<b>已存在</b>的账号只更新展示资料
     *       （昵称/签名/头像），<b>绝不覆盖其 passwordHash/salt</b>；凭据只在「重建已删除账号」
     *       时写入。这样别人无法用一份构造的备份顶掉本机现有账号的密码。</li>
     * </ul>
     * <p>因此「备份跨账号」不是越权漏洞，而是本地迁移场景下的有意设计，配合导入侧的
     * 凭据保护与系统备份关闭共同收口风险。</p>
     */
    public File exportBackup() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File zipFile = new File(backupDir, "streak_backup_" + timestamp + ".zip");

        // 备份是整机全量：导出所有账号的习惯（含 ownerUsername 归属）与所有账号资料，
        // 而非仅当前登录账号。这样导入既能在登录页（未登录）触发，又能完整恢复各账号数据。
        List<HabitItem> habits = habitDao.getAll();
        if (habits == null) {
            habits = new ArrayList<>();
        }
        // 打卡真相源已是 check_in_records 表。为向后兼容旧版本 App（读 habits.json 的
        // completedDates/notes），导出前把记录聚合回填进各习惯的这两个视图字段——
        // 旧版本导入仍能拿到打卡日期与备注。心情/耗时/照片这类新字段旧版本无法表达，
        // 故另存全保真的 check_in_records.json（见下），新版本导入优先用它。
        for (HabitItem habit : habits) {
            aggregateCheckInsInto(habit);
        }
        List<CheckInRecord> allRecords = checkInRecordDao.getAll();
        if (allRecords == null) {
            allRecords = new ArrayList<>();
        }
        List<UserAccount> accounts = loadAccounts();
        List<UserAccount> safeAccounts = sanitizeAccountsForExport(accounts);
        String exportedAt = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            // 0) 顶层版本化信封 backup.json：显式携带 schemaVersion，导入据此分派兼容转换，
            //    不再靠「某个 JSON 文件是否存在」来推断版本。新版本导入优先读它。
            BackupEnvelope envelope = new BackupEnvelope(
                    BackupEnvelope.CURRENT_SCHEMA_VERSION, exportedAt, habits, allRecords, safeAccounts);
            writeZipEntry(zos, "backup.json", gson.toJson(envelope).getBytes(StandardCharsets.UTF_8));

            // 1) 习惯 JSON（带导出时间；completedDates/notes 已回填）。保留此条目是为了让
            //    只认 habits.json 的旧版本 App 仍能导入（向后兼容），新版本改读 backup.json。
            HabitBackup backup = new HabitBackup(exportedAt, habits);
            writeZipEntry(zos, "habits.json", gson.toJson(backup).getBytes(StandardCharsets.UTF_8));

            // 2) 账号资料 JSON：含用户名/昵称/签名/头像 + PBKDF2 哈希与盐（绝不含明文密码）。
            //    导出哈希+盐是为了删号后导入能用原密码登回；注意备份文件可被外传，
            //    其中的哈希+盐可用于离线暴力破解，请提醒用户妥善保管备份。旧版本兼容用。
            writeZipEntry(zos, "accounts.json",
                    gson.toJson(safeAccounts).getBytes(StandardCharsets.UTF_8));

            // 3) 打卡记录 JSON（全保真：含心情/耗时/照片）。旧版本兼容用；新版本改读 backup.json。
            writeZipEntry(zos, "check_in_records.json",
                    gson.toJson(allRecords).getBytes(StandardCharsets.UTF_8));

            // 4) 所有引用到的图片（习惯照片 + 头像 + 打卡照片），打包进 images/ 目录，文件名即原名
            for (HabitItem habit : habits) {
                addImageToZip(zos, habit.getImageUri());
            }
            for (UserAccount account : accounts) {
                addImageToZip(zos, account.getAvatarUri());
            }
            for (CheckInRecord record : allRecords) {
                addImageToZip(zos, record.getPhotoUri());
            }
        } catch (Exception e) {
            // 打包失败（磁盘满、写盘异常等）：删掉半成品文件并返回 null，
            // 让上层据此提示失败，而不是把空/损坏的 zip 当成功文件继续保存。
            //noinspection ResultOfMethodCallIgnored
            zipFile.delete();
            return null;
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

    private void addImageToZip(ZipOutputStream zos, String fileUriOrPath) throws Exception {
        File source = resolveImageFile(fileUriOrPath);
        if (source == null || !source.exists()) {
            // 单张图片缺失/被清理：容错跳过，不影响整体备份成功。
            return;
        }
        // 写入 ZIP 流的异常（磁盘满等）不吞：向上抛出，让 exportBackup 删半成品并返回 null，
        // 避免用户拿到「缺图但显示成功」的损坏备份。
        zos.putNextEntry(new ZipEntry("images/" + source.getName()));
        try (FileInputStream fis = new FileInputStream(source)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                zos.write(buffer, 0, read);
            }
        }
        zos.closeEntry();
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
     * 从 ZIP 备份恢复。事务性保证：
     * 1) 先把所有条目读入内存并校验 habits.json 可解析；
     * 2) 落地图片，记录「本次新建」的文件以便失败时回滚（不误删备份前已存在的同名图片）；
     * 3) 在内存里算好最终的 habits + accounts（此时才能按已落地的图片重映射路径）；
     * 4) 用 {@link StreakDatabase#runInTransaction} 把两张表的整表替换绑成一个原子提交——
     *    要么习惯与账号一起更新成功，要么一起回滚，杜绝「习惯已替换但账号写入失败」的半成品状态；
     * 5) 事务抛异常时，Room 自动回滚 DB，本方法再删掉本次新写的图片，使磁盘状态一并复原。
     * 返回是否成功。
     */
    public boolean importBackup(Uri zipUri) {
        // 本次新落地的图片文件，供失败回滚时删除（只删新建的，不动备份前已存在的同名文件）
        List<File> newlyWrittenImages = new ArrayList<>();
        // 被本次导入覆盖的同名旧图：覆盖前先把原文件挪到 .import_bak 临时副本，
        // 成功后删除副本，失败时用副本还原——否则同名旧图被覆盖后无法恢复。
        List<File[]> overwrittenBackups = new ArrayList<>();
        try (InputStream rawInput = context.getContentResolver().openInputStream(zipUri)) {
            if (rawInput == null) {
                return false;
            }
            byte[] envelopeJson = null;
            byte[] habitsJson = null;
            byte[] accountsJson = null;
            byte[] recordsJson = null;
            Map<String, byte[]> images = new HashMap<>();

            // 第一遍：全部读入内存，不写磁盘。累计条目数与解压字节，超上限即放弃（防 OOM）。
            long[] budget = {MAX_TOTAL_BYTES};
            int entryCount = 0;
            try (ZipInputStream zis = new ZipInputStream(rawInput)) {
                ZipEntry entry;
                byte[] buffer = new byte[8192];
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory()) {
                        zis.closeEntry();
                        continue;
                    }
                    if (++entryCount > MAX_ENTRY_COUNT) {
                        return false;
                    }
                    if ("backup.json".equals(name)) {
                        envelopeJson = readEntryBytes(zis, buffer, budget);
                    } else if ("habits.json".equals(name)) {
                        habitsJson = readEntryBytes(zis, buffer, budget);
                    } else if ("accounts.json".equals(name)) {
                        accountsJson = readEntryBytes(zis, buffer, budget);
                    } else if ("check_in_records.json".equals(name)) {
                        recordsJson = readEntryBytes(zis, buffer, budget);
                    } else if (name.startsWith("images/")) {
                        String fileName = name.substring("images/".length());
                        if (isSafeImageName(fileName)) {
                            images.put(fileName, readEntryBytes(zis, buffer, budget));
                        }
                    }
                    zis.closeEntry();
                }
            }

            // 按显式版本号分派，而非「靠某文件是否存在」推断版本：
            // 有 backup.json（v3+ 新结构）就以它为准，读出 schemaVersion / habits / checkInRecords /
            // accounts；否则回退到旧的散装文件（habits.json 必需，check_in_records.json/accounts.json 可选）。
            // rawRecords==null 表示「备份未携带记录」——此时从 habits 的 completedDates/notes 重建。
            List<HabitItem> parsedHabits = null;
            List<CheckInRecord> parsedRecords = null;
            List<UserAccount> parsedAccounts = null;

            // 先尝试版本化信封 backup.json；只有它成功解析出习惯列表才采用。
            // 信封损坏（非法 JSON / 无 habits）时不直接放弃，而是回退到旧的分文件结构，
            // 尽量救回还能读的 habits.json，避免一份可用备份因新信封损坏而整体导入失败。
            if (envelopeJson != null) {
                BackupEnvelope envelope = parseEnvelope(envelopeJson);
                if (envelope != null && envelope.getHabits() != null) {
                    parsedHabits = envelope.getHabits();
                    parsedRecords = envelope.getCheckInRecords();
                    parsedAccounts = envelope.getAccounts();
                }
            }
            // 无可用信封时回退旧结构：habits.json 必须能解析出习惯列表，否则放弃、不动现有数据。
            if (parsedHabits == null) {
                if (habitsJson == null) {
                    return false;
                }
                HabitBackup backup = parseHabitBackup(habitsJson);
                if (backup == null || backup.getHabits() == null) {
                    return false;
                }
                parsedHabits = backup.getHabits();
                parsedRecords = parseRecordsJson(recordsJson);
                parsedAccounts = parseAccountsJson(accountsJson);
            }

            final List<HabitItem> rawHabits = parsedHabits;
            final List<CheckInRecord> rawRecords = parsedRecords;
            final List<UserAccount> rawImportedAccounts = parsedAccounts;

            // 校验通过，落地图片。记录「本次新建」的文件（原本不存在者），失败时据此回滚。
            for (Map.Entry<String, byte[]> img : images.entrySet()) {
                File out = new File(imageDir, img.getKey());
                // 规范化路径，确保仍在 imageDir 内（防 Zip Slip）
                if (!out.getCanonicalPath().startsWith(imageDir.getCanonicalPath() + File.separator)) {
                    continue;
                }
                boolean existedBefore = out.exists();
                if (existedBefore) {
                    // 覆盖同名旧图前，先把原文件挪到临时副本，供失败时还原。
                    File bak = new File(out.getParentFile(), out.getName() + ".import_bak");
                    //noinspection ResultOfMethodCallIgnored
                    bak.delete(); // 清理可能残留的上次副本
                    if (out.renameTo(bak)) {
                        overwrittenBackups.add(new File[]{out, bak});
                    } else {
                        // 备份副本创建失败：若继续覆盖，同名旧图将永久丢失且无从还原。
                        // 立即中止本次导入（抛异常触发下方 catch 的整体回滚），
                        // 保证磁盘与 DB 一并停留在导入前状态，不留下无法恢复的破坏。
                        throw new IOException("无法为将被覆盖的图片创建可回滚副本: " + out.getName());
                    }
                }
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(img.getValue());
                }
                if (!existedBefore) {
                    newlyWrittenImages.add(out);
                }
            }

            // 在内存里备好最终的习惯列表（图片已落地，可安全重映射路径）。
            // 备份是整机全量（含各账号习惯），故整表替换而非按当前账号 scoped 写入
            // （登录页未登录也能触发导入）。缺归属的旧备份数据补上演示账号 student，保持数据隔离。
            final List<HabitItem> habits = rawHabits;
            for (HabitItem habit : habits) {
                habit.setImageUri(remapImageUri(habit.getImageUri()));
                if (habit.getOwnerUsername() == null || habit.getOwnerUsername().isEmpty()) {
                    habit.setOwnerUsername("student");
                }
            }

            // 在内存里算好最终账号列表：同名账号只更新展示资料（绝不覆盖凭据），
            // 已删除（不存在）的账号则连凭据一并重建，使删号后导入能用原密码登回。
            // 打卡记录：优先用全保真的 check_in_records.json；旧备份没有该文件时，
            // 回退到从 habits 的 completedDates/notes 重建（只含日期与备注，无心情/耗时/照片）。
            final List<CheckInRecord> finalRecords = new ArrayList<>();
            if (recordsJson != null) {
                Type recType = new TypeToken<List<CheckInRecord>>() {}.getType();
                List<CheckInRecord> importedRecords = gson.fromJson(
                        new String(recordsJson, StandardCharsets.UTF_8), recType);
                if (importedRecords != null) {
                    for (CheckInRecord record : importedRecords) {
                        if (record == null || record.getDate() == null || record.getDate().isEmpty()) {
                            continue;
                        }
                        record.setPhotoUri(remapImageUri(record.getPhotoUri()));
                        record.setId(0);
                        finalRecords.add(record);
                    }
                }
            } else {
                for (HabitItem habit : habits) {
                    List<String> dates = habit.getCompletedDates();
                    if (dates == null) {
                        continue;
                    }
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    for (String date : dates) {
                        if (date == null || date.isEmpty() || !seen.add(date)) {
                            continue;
                        }
                        CheckInRecord record = new CheckInRecord();
                        record.setHabitId(habit.getId());
                        record.setDate(date);
                        String note = habit.getNote(date);
                        record.setNote(note == null || note.isEmpty() ? null : note);
                        finalRecords.add(record);
                    }
                }
            }

            final List<UserAccount> finalAccounts;
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
                            // 安全：已存在的账号只更新展示资料，绝不覆盖其凭据(passwordHash/salt)。
                            // 否则任何人都能用一份构造的备份替换本机已有账号的密码哈希，
                            // 造成账号接管/密码被顶掉。凭据仅在「重建缺失账号」分支写入。
                            match.setDisplayName(importedAccount.getDisplayName());
                            match.setMotto(importedAccount.getMotto());
                            // 备份缺图时 remap 返回 null，此时保留现有头像而非清空
                            String remappedAvatar = remapImageUri(importedAccount.getAvatarUri());
                            if (remappedAvatar != null) {
                                match.setAvatarUri(remappedAvatar);
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
                    finalAccounts = current;
                } else {
                    finalAccounts = null;
                }
            } else {
                finalAccounts = null;
            }

            // 快照导入前的全部习惯 id：整表替换会删掉未包含在备份里的旧习惯，
            // 但它们的闹钟（PendingIntent）不会自动消失。事务提交后要逐个取消，
            // 否则旧闹钟仍会触发，且 ReminderReceiver 查不到习惯时会用旧 Intent 发一条通知。
            List<HabitItem> preImportHabits = habitDao.getAll();
            List<Long> preImportIds = new ArrayList<>();
            for (HabitItem habit : preImportHabits) {
                preImportIds.add(habit.getId());
            }

            // 原子提交：习惯 + 账号两张表在同一事务里整表替换。
            // 中途任一步抛异常，Room 回滚整笔事务，DB 停留在导入前状态。
            database.runInTransaction(() -> {
                habitDao.replaceAll(habits);
                checkInRecordDao.clear();
                if (!finalRecords.isEmpty()) {
                    checkInRecordDao.upsertAll(finalRecords);
                }
                if (finalAccounts != null) {
                    saveAccounts(finalAccounts);
                }
            });

            // 导入成功：被覆盖的同名旧图已无需还原，删掉临时副本。
            for (File[] pair : overwrittenBackups) {
                //noinspection ResultOfMethodCallIgnored
                pair[1].delete();
            }

            // 先取消导入前所有习惯的旧闹钟，再按导入后的数据重建，
            // 避免被删除习惯的 PendingIntent 残留触发。
            for (long oldId : preImportIds) {
                reminderScheduler.cancel(oldId);
            }
            rescheduleAllReminders();
            return true;
        } catch (Exception e) {
            // DB 已由事务回滚；再复原磁盘上的图片，使磁盘状态一并回到导入前，不留孤儿文件。
            // 1) 删掉本次新写的图片（原本不存在的）。
            for (File orphan : newlyWrittenImages) {
                //noinspection ResultOfMethodCallIgnored
                orphan.delete();
            }
            // 2) 用临时副本还原被覆盖的同名旧图：先删掉本次写入的新内容，再把副本改回原名。
            for (File[] pair : overwrittenBackups) {
                File out = pair[0];
                File bak = pair[1];
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                //noinspection ResultOfMethodCallIgnored
                bak.renameTo(out);
            }
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

    /**
     * 解析版本化信封 backup.json。损坏/非法 JSON 返回 null（由 importBackup 回退旧结构或放弃），
     * 绝不因解析异常崩溃或误改现有数据。
     */
    private BackupEnvelope parseEnvelope(byte[] json) {
        if (json == null) {
            return null;
        }
        try {
            return gson.fromJson(new String(json, StandardCharsets.UTF_8), BackupEnvelope.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析旧结构 habits.json（HabitBackup）。损坏返回 null。 */
    private HabitBackup parseHabitBackup(byte[] json) {
        if (json == null) {
            return null;
        }
        try {
            return gson.fromJson(new String(json, StandardCharsets.UTF_8), HabitBackup.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析旧结构 check_in_records.json。损坏或缺失返回 null（回退到 completedDates 重建）。 */
    private List<CheckInRecord> parseRecordsJson(byte[] json) {
        if (json == null) {
            return null;
        }
        try {
            Type recType = new TypeToken<List<CheckInRecord>>() {}.getType();
            return gson.fromJson(new String(json, StandardCharsets.UTF_8), recType);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析旧结构 accounts.json。损坏或缺失返回 null（此时不动现有账号）。 */
    private List<UserAccount> parseAccountsJson(byte[] json) {
        if (json == null) {
            return null;
        }
        try {
            Type type = new TypeToken<List<UserAccount>>() {}.getType();
            return gson.fromJson(new String(json, StandardCharsets.UTF_8), type);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读取单个 ZIP 条目内容，并施加两道上限防 OOM/解压炸弹：
     * 单条目不得超过 {@link #MAX_ENTRY_BYTES}，且累计解压量不得超过 budget（总预算，跨条目共享）。
     * 任一超限即抛异常，由 importBackup 统一 catch 成失败并放弃导入（不动现有数据）。
     * budget 为单元素数组，用于把剩余预算按引用回传给调用方累计扣减。
     */
    private byte[] readEntryBytes(ZipInputStream zis, byte[] buffer, long[] budget) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        long entryBytes = 0;
        int read;
        while ((read = zis.read(buffer)) != -1) {
            entryBytes += read;
            if (entryBytes > MAX_ENTRY_BYTES) {
                throw new java.io.IOException("单个备份条目超过大小上限");
            }
            budget[0] -= read;
            if (budget[0] < 0) {
                throw new java.io.IOException("备份解压总量超过上限");
            }
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

    /**
     * 删除某习惯所有打卡记录里附带的照片文件（每天打卡可各自配一张，与习惯自身 imageUri 独立）。
     * 删习惯/删号时调用，避免记录行删了但磁盘照片成孤儿文件。
     */
    private void deleteCheckInPhotos(long habitId) {
        List<CheckInRecord> records = checkInRecordDao.getByHabit(habitId);
        if (records == null) {
            return;
        }
        for (CheckInRecord record : records) {
            deletePhoto(record.getPhotoUri());
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
        // 纯读取：默认账号的初始化已收敛进 initializeStorageIfNeeded()（仅全新安装触发一次），
        // 这里不再「表空即补默认账号」，避免老用户删号后又被塞回 student、以及与迁移交错重复写入。
        List<UserAccount> accounts = userDao.getAll();
        return accounts == null ? new ArrayList<>() : accounts;
    }

    private List<UserAccount> defaultAccounts() {
        UserAccount student = new UserAccount();
        student.setUsername("student");
        applyHashedPassword(student, "123456");
        List<UserAccount> defaults = new ArrayList<>();
        defaults.add(student);
        return defaults;
    }

    /**
     * 确保给定用户名的账号存在，缺失则补一个默认演示账号（密码 123456）。
     * 用于旧数据迁移：迁来的习惯统一归属 student，但旧账号表里未必有 student，
     * 补齐后这些习惯才有可登录的归属，不至于成为看不到的孤儿数据。
     */
    private void ensureAccountExists(String username) {
        if (username == null || username.isEmpty()) {
            return;
        }
        if (userDao.findByUsername(username) != null) {
            return;
        }
        UserAccount account = new UserAccount();
        account.setUsername(username);
        applyHashedPassword(account, "123456");
        userDao.upsert(account);
    }

    private void saveAccounts(List<UserAccount> accounts) {
        // 整体替换：清空后批量写入，事务保证一致性（等价旧的整文件覆盖语义）。
        userDao.replaceAll(accounts == null ? new ArrayList<>() : accounts);
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
        // 种子习惯归属演示账号 student，与 Room v1->v2 迁移里给存量习惯的归属保持一致。
        for (HabitItem habit : seed) {
            habit.setOwnerUsername("student");
        }
        return seed;
    }

}
