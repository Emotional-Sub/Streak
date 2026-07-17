package com.streak.app.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.streak.app.db.CheckInRecordDao;
import com.streak.app.data.BackupService;
import com.streak.app.data.CheckInRepository;
import com.streak.app.data.HabitRepository;
import com.streak.app.data.AuthRepository;
import com.streak.app.data.UserRepository;
import com.streak.app.db.HabitDao;
import com.streak.app.db.StreakDatabase;
import com.streak.app.db.UserDao;
import com.streak.app.model.BackupEnvelope;
import com.streak.app.model.CameraCaptureInfo;
import com.streak.app.model.CheckInRecord;
import com.streak.app.model.HabitBackup;
import com.streak.app.model.HabitItem;
import com.streak.app.model.UserAccount;
import com.streak.app.data.ImageStore;
import com.streak.app.data.ReminderManager;
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


    private final Context context;
    private final SharedPreferences preferences;
    private final Gson gson = new Gson();
    // 旧 JSON 文件仅用于首启迁移与损坏文件归档；日常读写走 Room。
    private final File habitsFile;
    private final File accountsFile;
    private final File imageDir;
    private final File backupDir;
    private final ReminderManager reminderManager;
    private final ImageStore imageStore;
    private final StreakDatabase database;
    private final HabitDao habitDao;
    private final UserDao userDao;
    private final CheckInRecordDao checkInRecordDao;
    private final CheckInRepository checkInRepository;
    private final HabitRepository habitRepository;
    private final BackupService backupService;
    private final AuthRepository authRepository;
    private final UserRepository userRepository;

    public AppRepository(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.habitsFile = new File(this.context.getFilesDir(), "habits.json");
        this.accountsFile = new File(this.context.getFilesDir(), "accounts.json");
        this.imageDir = new File(this.context.getFilesDir(), "habit_images");
        this.backupDir = new File(this.context.getFilesDir(), "exports");
        this.imageDir.mkdirs();
        this.backupDir.mkdirs();
        this.reminderManager = new ReminderManager(this.context);
        this.imageStore = new ImageStore(this.context, this.imageDir);
        this.database = StreakDatabase.getInstance(this.context);
        this.habitDao = database.habitDao();
        this.userDao = database.userDao();
        this.checkInRecordDao = database.checkInRecordDao();
        this.checkInRepository = new CheckInRepository(this.checkInRecordDao, this.imageStore);
        this.habitRepository = new HabitRepository(this.database, this.habitDao, this.checkInRepository, this::getCurrentUser);
        this.authRepository = new AuthRepository(this.context);
        this.userRepository = new UserRepository(this.database, this.userDao, this.habitDao, this::getCurrentUser);
        this.backupService = new BackupService(this.context, this.database, this.habitDao,
                this.checkInRecordDao, this.imageStore, this.checkInRepository, this.userRepository,
                this.reminderManager, this.imageDir, this.backupDir, this::rescheduleAllReminders);
        purgeLegacyPlaintextPassword();
        initializeStorageIfNeeded();
    }

    /**
     * 清除历史版本明文存储在 SharedPreferences 里的密码（安全整改）。
     * 现在只记住用户名，不再回填/持久化任何密码。
     */
    /** 清除历史遗留的明文密码（安全整改）。转发 {@link AuthRepository}。 */
    private void purgeLegacyPlaintextPassword() {
        authRepository.purgeLegacyPlaintextPassword();
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
        return userRepository.validateLogin(username, password);
    }

        public String registerAccount(String username, String password) {
        return userRepository.registerAccount(username, password);
    }

    /**
     * 判断候选密码是否与账号当前密码一致（兼容旧明文账号）。
     */
    /**
     * 保存登录态。安全整改：只在「记住用户名」勾选时保存用户名，绝不再持久化密码。
     * 保留 password 形参是为了兼容调用方签名，但不写入任何存储。
     */
    public void saveLoginState(String username, String password, boolean rememberPassword, String currentUser) {
        authRepository.saveLoginState(username, password, rememberPassword, currentUser);
    }


    public void logout() {
        // 退出前取消本账号所有习惯的提醒（否则退出后旧账号闹钟仍续排），再清空当前登录用户名。
        // 必须在清空前取消——cancelRemindersForCurrentUser 依赖 getCurrentUser 定位当前账号习惯。
        cancelRemindersForCurrentUser();
        authRepository.clearCurrentUser();
    }


    /** 取消当前登录账号名下所有习惯的提醒闹钟（退出/切换账号时用）。 */
    private void cancelRemindersForCurrentUser() {
        reminderManager.cancelAll(readHabits());
    }

    public String getSavedUsername() {
        return authRepository.getSavedUsername();
    }


    /** 是否记住用户名（复选框语义已从「记住密码」收敛为「记住用户名」）。 */
    public boolean isRememberPassword() {
        return authRepository.isRememberPassword();
    }


    public String getCurrentUser() {
        return authRepository.getCurrentUser();
    }


    /** 读取主题模式偏好，默认跟随系统。 */
    public int getThemeMode() {
        return authRepository.getThemeMode();
    }


    public void setThemeMode(int mode) {
        authRepository.setThemeMode(mode);
    }


        public UserAccount getAccount(String username) {
        return userRepository.getAccount(username);
    }

        public UserAccount getCurrentAccount() {
        return userRepository.getCurrentAccount();
    }

        public void updateProfile(String username, String displayName, String motto, String avatarUri) {
        userRepository.updateProfile(username, displayName, motto, avatarUri);
    }

    /**
     * 编辑账号：可同时修改用户名（查重）、昵称、签名、头像、密码。
     * newPassword 为空表示不改密码。返回 null 表示成功，否则返回错误信息。
     */
        public String updateAccount(String oldUsername, String newUsername, String displayName,
                                String motto, String avatarUri, String newPassword) {
        String error = userRepository.updateAccount(
                oldUsername, newUsername, displayName, motto, avatarUri, newPassword);
        // 账号数据改名成功后，再同步会话态（current_user/saved_username）——
        // 账号表与会话态分属 UserRepository/AuthRepository，由门面在此编排先后。
        if (error == null && newUsername != null) {
            authRepository.syncRenamedUser(oldUsername, newUsername.trim());
        }
        return error;
    }

    /**
     * 删除当前账号，并只清空「本账号」的习惯/打卡及其照片（数据隔离后不再动其它账号）。
     * 只删除本账号头像，保留其它账号的头像文件。
     */
    public void deleteCurrentAccountAndData() {
        String username = getCurrentUser();
        List<UserAccount> accounts = loadAccounts();
        List<UserAccount> remaining = new ArrayList<>();
        // 先收集要删的图片路径（头像/习惯图/打卡照片），先不删文件——
        // 等 DB 事务成功提交后再删，避免事务回滚却已把文件删掉，
        // 留下 DB 有记录、磁盘无图的悬空引用。
        List<String> imagesToDelete = new ArrayList<>();
        for (UserAccount account : accounts) {
            if (!username.equals(account.getUsername())) {
                remaining.add(account);
            } else {
                imagesToDelete.add(account.getAvatarUri());
            }
        }

        // 趁登录态仍在，取回本账号习惯（readHabits 依赖 getCurrentUser()）：
        // 收集习惯图与打卡照片路径，并取消提醒（提醒非 DB 数据，可提前取消）。
        List<HabitItem> ownHabits = readHabits();
        for (HabitItem habit : ownHabits) {
            reminderManager.cancel(habit.getId());
            imagesToDelete.add(habit.getImageUri());
            // 打卡记录里附带的照片（与习惯自身 imageUri 独立）
            for (CheckInRecord record : getCheckIns(habit.getId())) {
                imagesToDelete.add(record.getPhotoUri());
            }
        }

        // 账号删除的所有 DB 操作放进一个事务：删打卡记录、删习惯、替换账号表一并成败。
        // 打卡记录靠 habits 子查询定位归属，必须在删 habits 行之前先删记录。
        if (username != null && !username.isEmpty()) {
            final String owner = username;
            database.runInTransaction(() -> {
                checkInRecordDao.deleteByOwner(owner);
                habitDao.clearByOwner(owner);
                saveAccounts(remaining);
            });
        } else {
            saveAccounts(remaining);
        }

        // 事务成功提交后再删图片文件：此时 DB 已无引用，删文件失败也不影响数据一致性。
        for (String uri : imagesToDelete) {
            deletePhoto(uri);
        }

        logout();
        authRepository.clearSavedUsername();
    }

    /**
     * 把拍照/相册得到的图片复制进头像目录，返回 file:// uri。
     */
    public String copyAvatarImage(Uri uri) {
        return imageStore.copyAvatarImage(uri);
    }

        public List<HabitItem> readHabits() {
        return habitRepository.readHabits();
    }

    /**
     * 批量取回给定习惯的打卡记录并按 habitId 分组（消除 N+1）。空列表直接返回空 map，
     * 避免拼出 {@code IN ()} 空集查询。
     */
    private Map<Long, List<CheckInRecord>> recordsGroupedByHabit(List<Long> habitIds) {
        return checkInRepository.recordsGroupedByHabit(habitIds);
    }

    /**
     * 把某习惯在 check_in_records 表里的打卡记录聚合回填进它的内存派生字段
     * （completedDates / notes），使既有统计/展示代码无需改动即可继续读。
     * 记录表是唯一真相源，此处只是「读时物化」出旧的两个视图字段。
     */
    private void aggregateCheckInsInto(HabitItem habit) {
        checkInRepository.aggregateInto(habit);
    }

    /**
     * 用「已取好的记录列表」把打卡数据聚合回填进内存派生字段，避免重复查库。
     * readHabits 批量取记录后按 habitId 分组，对每个习惯调用此重载（消除 N+1）。
     */
    private void aggregateCheckInsInto(HabitItem habit, List<CheckInRecord> records) {
        checkInRepository.aggregateInto(habit, records);
    }

        public void writeHabits(List<HabitItem> habits) {
        habitRepository.writeHabits(habits);
    }

        public HabitItem findHabitById(long habitId) {
        return habitRepository.findHabitById(habitId);
    }

    /** 取某习惯某天的打卡记录（含心情/耗时/照片），无则 null。供新打卡 UI/详情页读富字段。 */
    public CheckInRecord getCheckIn(long habitId, String date) {
        return checkInRepository.getCheckIn(habitId, date);
    }

    /**
     * 取某习惯全部打卡记录，按日期降序（最近在前）。供详情页时间线直接读富字段
     * （心情/耗时/照片），不经 completedDates/notes 过渡视图。
     */
    public List<CheckInRecord> getCheckIns(long habitId) {
        return checkInRepository.getCheckIns(habitId);
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
        checkInRepository.upsertCheckIn(habitId, date, mood, durationMinutes, note, photoUri);
    }

    /**
     * 撤销某习惯某天的打卡：删记录表里的那一条，并删掉其附带的打卡照片文件（避免孤儿图）。
     * 直写真相源，不经派生字段。
     */
    public void removeCheckIn(long habitId, String date) {
        checkInRepository.removeCheckIn(habitId, date);
    }

    /**
     * 生成全表唯一的习惯 id（以当前毫秒为基准，占用则递增）。
     * id 是全表主键，必须对整表防撞——不能只在当前账号内查，否则两个账号在同一毫秒
     * 新建可能撞 id，upsert 的 REPLACE 会跨账号覆盖，破坏数据隔离。
     */
        public long generateUniqueHabitId() {
        return habitRepository.generateUniqueHabitId();
    }

    /**
     * 保存单个习惯（新增或更新，按 id 主键 upsert）。
     * 相比整表 writeHabits，只动这一行，避免「读全量→改一条→写全量」在并发下
     * 用过期快照覆盖掉其它习惯的改动（补卡、编辑、提醒回执可能同时发生）。
     * 新习惯若未设归属，则盖上当前登录账号，保证数据隔离。
     */
        public void saveHabit(HabitItem habit) {
        habitRepository.saveHabit(habit);
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
        checkInRepository.syncFrom(habit);
    }

    /**
     * 按 id 删除单个习惯，并限定归属为当前账号。同样只动一行，不整表覆盖，避免并发丢更新。
     * 带 owner 过滤是数据隔离的防御措施：即便传入其它账号的 id 也删不到，不会跨账号误删。
     */
        public void deleteHabitById(long habitId) {
        habitRepository.deleteHabitById(habitId);
    }

    /**
     * 导出整机全量备份（ZIP）。委托 {@link BackupService}。失败返回 null。
     */
    public File exportBackup() {
        return backupService.exportBackup();
    }

    /**
     * 从 ZIP 备份恢复。委托 {@link BackupService}。返回是否成功。
     */
    public boolean importBackup(Uri zipUri) {
        return backupService.importBackup(zipUri);
    }

    public void syncReminder(HabitItem habit) {
        reminderManager.schedule(habit);
    }

    /**
     * 重新调度所有开启提醒的习惯，用于开机后恢复闹钟。
     */
    public void rescheduleAllReminders() {
        reminderManager.scheduleAll(readHabits());
    }

    public void cancelReminder(long habitId) {
        reminderManager.cancel(habitId);
    }

    public CameraCaptureInfo createCameraCapture() {
        return imageStore.createCameraCapture();
    }

    public String persistCapturedPhoto(String filePath) {
        return imageStore.persistCapturedPhoto(filePath);
    }

    public void deletePhoto(String filePathOrUri) {
        imageStore.deletePhoto(filePathOrUri);
    }

    /**
     * 删除某习惯所有打卡记录里附带的照片文件（每天打卡可各自配一张，与习惯自身 imageUri 独立）。
     * 删习惯/删号时调用，避免记录行删了但磁盘照片成孤儿文件。
     */
    private void deleteCheckInPhotos(long habitId) {
        checkInRepository.deleteCheckInPhotos(habitId);
    }

    public String copyGalleryImage(Uri uri) {
        return imageStore.copyGalleryImage(uri);
    }

    /**
     * 把二维码 Bitmap 保存到系统相册的 Pictures/Streak 目录。
     * API 29+ 走 MediaStore（免存储权限）；API 26-28 需调用方先拿到 WRITE_EXTERNAL_STORAGE。
     * 返回保存后的图片 Uri；失败返回 null。注意：应在后台线程调用，避免阻塞主线程。
     */
    public Uri saveQrToGallery(android.graphics.Bitmap bitmap, String displayName) {
        return imageStore.saveQrToGallery(bitmap, displayName);
    }

    /**
     * 把 Bitmap 写入 cache/shares 目录并返回可对外分享的 FileProvider content:// uri。
     * 用于成就战报等临时图片分享，失败返回 null。应在后台线程调用。
     */
    public Uri cacheBitmapForShare(android.graphics.Bitmap bitmap, String baseName) {
        return imageStore.cacheBitmapForShare(bitmap, baseName);
    }

    private String sanitizeFileName(String name) {
        return imageStore.sanitizeFileName(name);
    }

        private List<UserAccount> loadAccounts() {
        return userRepository.loadAccounts();
    }

        private List<UserAccount> defaultAccounts() {
        return userRepository.defaultAccounts();
    }

    /**
     * 确保给定用户名的账号存在，缺失则补一个默认演示账号（密码 123456）。
     * 用于旧数据迁移：迁来的习惯统一归属 student，但旧账号表里未必有 student，
     * 补齐后这些习惯才有可登录的归属，不至于成为看不到的孤儿数据。
     */
        private void ensureAccountExists(String username) {
        userRepository.ensureAccountExists(username);
    }

        private void saveAccounts(List<UserAccount> accounts) {
        userRepository.saveAccounts(accounts);
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
