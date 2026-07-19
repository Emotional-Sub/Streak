package com.streak.app.data;

import android.content.Context;
import android.net.Uri;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.streak.app.db.CheckInRecordDao;
import com.streak.app.db.HabitDao;
import com.streak.app.db.StreakDatabase;
import com.streak.app.model.BackupEnvelope;
import com.streak.app.model.CheckInRecord;
import com.streak.app.model.HabitBackup;
import com.streak.app.model.HabitItem;
import com.streak.app.model.UserAccount;
import com.streak.app.util.AvatarPresets;
import com.streak.app.util.PasswordHasher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 备份服务（Phase B 从 {@code AppRepository} 拆出）：ZIP 导入导出与跨版本兼容。
 *
 * <p><b>职责边界。</b>只负责整机全量备份的打包/解包与版本分派。习惯/账号/打卡的读写委托各自的
 * DAO 与 Repository，图片的 ZIP 写入与路径解析委托 {@link ImageStore}，提醒重排委托门面注入的
 * {@code rescheduleAll}（保持「按当前登录账号重排」的既有语义）。</p>
 *
 * <p><b>设计取舍：整机全量、非账号隔离。</b>备份刻意导出「本机所有账号」的习惯与资料，而非仅当前
 * 登录账号——导入可在登录页（未登录、无当前账号）触发，是本 App「离线优先、无后端」的跨设备/重装
 * 迁移方案；删号后仍能用同一份备份把账号连凭据一起恢复并用原密码登回。备份含各账号 PBKDF2 哈希+盐
 * （绝不含明文密码）；导入侧对已存在账号只更新展示资料、绝不覆盖凭据，防账号接管。</p>
 */
public class BackupService {

    // ZIP 导入安全上限：个人打卡备份体量很小，用保守阈值挡住恶意/超大 ZIP 撑爆内存。
    // 单条目 16MB（覆盖高清照片有余）、总解压 128MB、最多 500 个条目。
    private static final long MAX_ENTRY_BYTES = 16L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    private static final int MAX_ENTRY_COUNT = 500;

    private final Context context;
    private final Gson gson = new Gson();
    private final File imageDir;
    private final File backupDir;
    private final StreakDatabase database;
    private final HabitDao habitDao;
    private final CheckInRecordDao checkInRecordDao;
    private final ImageStore imageStore;
    private final CheckInRepository checkInRepository;
    private final UserRepository userRepository;
    private final ReminderManager reminderManager;
    private final Runnable rescheduleAll;

    public BackupService(Context context, StreakDatabase database, HabitDao habitDao,
                         CheckInRecordDao checkInRecordDao, ImageStore imageStore,
                         CheckInRepository checkInRepository, UserRepository userRepository,
                         ReminderManager reminderManager, File imageDir, File backupDir,
                         Runnable rescheduleAll) {
        this.context = context.getApplicationContext();
        this.database = database;
        this.habitDao = habitDao;
        this.checkInRecordDao = checkInRecordDao;
        this.imageStore = imageStore;
        this.checkInRepository = checkInRepository;
        this.userRepository = userRepository;
        this.reminderManager = reminderManager;
        this.imageDir = imageDir;
        this.backupDir = backupDir;
        this.rescheduleAll = rescheduleAll;
    }

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
            checkInRepository.aggregateInto(habit);
        }
        List<CheckInRecord> allRecords = checkInRecordDao.getAll();
        if (allRecords == null) {
            allRecords = new ArrayList<>();
        }
        List<UserAccount> accounts = userRepository.loadAccounts();
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
                imageStore.addImageToZip(zos, habit.getImageUri());
            }
            for (UserAccount account : accounts) {
                imageStore.addImageToZip(zos, account.getAvatarUri());
            }
            for (CheckInRecord record : allRecords) {
                imageStore.addImageToZip(zos, record.getPhotoUri());
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
            // 保留 ZIP 条目顺序，便于失败时按确定顺序回滚，也避免同一备份每次覆盖顺序不同。
            Map<String, byte[]> images = new LinkedHashMap<>();

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
                // 校验 schemaVersion：版本号缺失/非法(<=0)或高于本 App 能理解的上限
                // (CURRENT_SCHEMA_VERSION)，说明信封由更新版本产出、其结构可能不兼容——
                // 不信任它，回退到向后兼容的散装文件(habits.json 等)。只在版本可理解时才采用信封数据。
                // 目前只有 v4 有明确的转换实现。v4 信封的三个数据列表都是必需字段：
                // 缺任何一个都不能把「部分信封」当成完整备份，否则会静默丢富字段或账号。
                // 不支持的版本交给下面的散装文件兼容路径处理。
                if (envelope != null
                        && envelope.getSchemaVersion() == BackupEnvelope.CURRENT_SCHEMA_VERSION
                        && envelope.getHabits() != null
                        && envelope.getCheckInRecords() != null
                        && envelope.getAccounts() != null) {
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
                // 散装文件严格解析：区分「缺失」与「存在但损坏」。
                //   缺失(bytes==null) -> 返回 null，走兼容降级（记录从 completedDates 重建、账号保持不动）；
                //   存在但 JSON 损坏/类型错误/结构非法 -> 抛异常，由下方 catch 整体失败并回滚，
                //   绝不把损坏文件静默当成「缺失」而丢弃其本应携带的打卡富字段/账号数据。
                parsedRecords = parseRecordsJson(recordsJson);
                parsedAccounts = parseAccountsJson(accountsJson);
            }

            // 所有来源统一做语义校验，并且必须发生在图片落地/数据库事务之前。
            validateImportedStructure(parsedHabits, parsedRecords, parsedAccounts);

            final List<HabitItem> rawHabits = parsedHabits;
            // 已解析出的记录/账号（信封优先，否则来自旧散装文件）——下面构建 finalRecords/
            // finalAccounts 一律以这两个为源，不再重复解析散装 JSON，避免「只含 backup.json 的
            // 备份走进旧文件分支而丢失心情/耗时/照片/账号」。
            final List<CheckInRecord> sourceRecords = parsedRecords;
            final List<UserAccount> sourceAccounts = parsedAccounts;

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
            if (sourceRecords != null) {
                // 已解析的全保真记录（来自信封 backup.json 的 checkInRecords，或旧结构
                // check_in_records.json）。此前这里错误地重新只读散装 recordsJson——
                // 只含 backup.json 的备份因此丢失心情/耗时/照片。现直接用解析结果。
                for (CheckInRecord record : sourceRecords) {
                    if (record == null || record.getDate() == null || record.getDate().isEmpty()) {
                        continue;
                    }
                    record.setPhotoUri(remapImageUri(record.getPhotoUri()));
                    record.setId(0);
                    finalRecords.add(record);
                }
            } else {
                for (HabitItem habit : habits) {
                    List<String> dates = habit.getCompletedDates();
                    if (dates == null) {
                        continue;
                    }
                    Set<String> seen = new HashSet<>();
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
            if (sourceAccounts != null) {
                // 已解析的账号列表（信封 backup.json 的 accounts，或旧结构 accounts.json）。
                // 同前：此前错误地重新只读散装 accountsJson，只含 backup.json 的备份丢账号。
                List<UserAccount> current = userRepository.loadAccounts();
                for (UserAccount importedAccount : sourceAccounts) {
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
                    userRepository.saveAccounts(finalAccounts);
                }
            });

            // 导入成功：被覆盖的同名旧图已无需还原，删掉临时副本。
            for (File[] pair : overwrittenBackups) {
                try {
                    //noinspection ResultOfMethodCallIgnored
                    pair[1].delete();
                } catch (Exception ignored) {
                    // 临时副本清理失败不应把已提交的导入伪装成失败；最多留下可清理的孤立文件。
                }
            }

            // 先取消导入前所有习惯的旧闹钟，再按导入后的数据重建，
            // 避免被删除习惯的 PendingIntent 残留触发。
            for (long oldId : preImportIds) {
                try {
                    reminderManager.cancel(oldId);
                } catch (Exception ignored) {
                    // 提醒属于非关键副作用，不能破坏已提交的数据导入。
                }
            }
            try {
                rescheduleAll.run();
            } catch (Exception ignored) {
                // 同上：导入成功，提醒重排失败时后续启动/登录仍可再次重排。
            }
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

    /** 在任何磁盘/数据库写入前验证备份中的实体和跨表引用。 */
    private void validateImportedStructure(List<HabitItem> habits,
                                           List<CheckInRecord> records,
                                           List<UserAccount> accounts) throws IOException {
        if (habits == null) {
            throw new IOException("备份缺少习惯列表");
        }

        Set<Long> habitIds = new HashSet<>();
        for (HabitItem habit : habits) {
            if (habit == null || habit.getId() <= 0 || !habitIds.add(habit.getId())) {
                throw new IOException("备份包含空习惯、非法 id 或重复 id");
            }
        }

        if (records != null) {
            Set<String> recordKeys = new HashSet<>();
            for (CheckInRecord record : records) {
                if (record == null || record.getDate() == null
                        || record.getDate().trim().isEmpty()) {
                    throw new IOException("打卡记录为空或缺少日期");
                }
                if (!habitIds.contains(record.getHabitId())) {
                    throw new IOException("打卡记录引用了备份中不存在的习惯");
                }
                String key = record.getHabitId() + "\n" + record.getDate();
                if (!recordKeys.add(key)) {
                    throw new IOException("备份包含重复的习惯日期打卡记录");
                }
            }
        }

        if (accounts != null) {
            Set<String> usernames = new HashSet<>();
            for (UserAccount account : accounts) {
                if (account == null || account.getUsername() == null
                        || account.getUsername().trim().isEmpty()) {
                    throw new IOException("账号记录为空或缺少用户名");
                }
                if (!usernames.add(account.getUsername())) {
                    throw new IOException("备份包含重复用户名");
                }
                boolean hasHash = !isBlank(account.getPasswordHash()) && !isBlank(account.getSalt());
                boolean hasLegacyPassword = !isBlank(account.getPassword());
                if (!hasHash && !hasLegacyPassword) {
                    throw new IOException("账号缺少可恢复的登录凭据");
                }
            }
            for (HabitItem habit : habits) {
                String owner = habit.getOwnerUsername();
                if (owner != null && !owner.isEmpty() && !usernames.contains(owner)) {
                    throw new IOException("习惯归属账号不在备份账号列表中");
                }
            }
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 解析旧结构 check_in_records.json。
     *
     * <p><b>区分缺失与损坏。</b>文件缺失（{@code json==null}）返回 null，由调用方走兼容降级
     * （从 habits 的 completedDates/notes 重建记录）。但文件<b>存在却无法解析</b>（非法 JSON、
     * 类型不符、Gson 反序列化异常）时<b>抛出异常</b>——绝不静默当成「缺失」而丢弃这份本应携带的
     * 打卡记录，交由 {@link #importBackup} 的 catch 整体失败并回滚 DB 与图片。</p>
     */
    private List<CheckInRecord> parseRecordsJson(byte[] json) throws IOException {
        if (json == null) {
            return null;
        }
        Type recType = new TypeToken<List<CheckInRecord>>() {}.getType();
        List<CheckInRecord> parsed;
        try {
            parsed = gson.fromJson(new String(json, StandardCharsets.UTF_8), recType);
        } catch (Exception e) {
            throw new IOException("check_in_records.json 存在但无法解析（损坏或结构非法）", e);
        }
        if (parsed == null) {
            // 内容是字面量 "null" 或空——文件存在却给不出有效列表，视为损坏。
            throw new IOException("check_in_records.json 存在但解析结果为空（结构非法）");
        }
        return parsed;
    }

    /**
     * 解析旧结构 accounts.json。
     *
     * <p><b>区分缺失与损坏。</b>文件缺失（{@code json==null}）返回 null，此时不动现有账号。
     * 文件<b>存在却无法解析</b>（非法 JSON、类型不符如对象而非数组）时<b>抛出异常</b>——
     * 绝不静默当成「缺失」而放过一份损坏备份，交由 {@link #importBackup} 整体失败并回滚。</p>
     */
    private List<UserAccount> parseAccountsJson(byte[] json) throws IOException {
        if (json == null) {
            return null;
        }
        Type type = new TypeToken<List<UserAccount>>() {}.getType();
        List<UserAccount> parsed;
        try {
            parsed = gson.fromJson(new String(json, StandardCharsets.UTF_8), type);
        } catch (Exception e) {
            throw new IOException("accounts.json 存在但无法解析（损坏或结构非法）", e);
        }
        if (parsed == null) {
            throw new IOException("accounts.json 存在但解析结果为空（结构非法）");
        }
        return parsed;
    }

    /**
     * 读取单个 ZIP 条目内容，并施加两道上限防 OOM/解压炸弹：
     * 单条目不得超过 {@link #MAX_ENTRY_BYTES}，且累计解压量不得超过 budget（总预算，跨条目共享）。
     * 任一超限即抛异常，由 importBackup 统一 catch 成失败并放弃导入（不动现有数据）。
     * budget 为单元素数组，用于把剩余预算按引用回传给调用方累计扣减。
     */
    private byte[] readEntryBytes(ZipInputStream zis, byte[] buffer, long[] budget) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        long entryBytes = 0;
        int read;
        while ((read = zis.read(buffer)) != -1) {
            entryBytes += read;
            if (entryBytes > MAX_ENTRY_BYTES) {
                throw new IOException("单个备份条目超过大小上限");
            }
            budget[0] -= read;
            if (budget[0] < 0) {
                throw new IOException("备份解压总量超过上限");
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
        File source = imageStore.resolveImageFile(original);
        if (source == null) {
            return null;
        }
        File local = new File(imageDir, source.getName());
        if (local.exists()) {
            return Uri.fromFile(local).toString();
        }
        return null;
    }
}
