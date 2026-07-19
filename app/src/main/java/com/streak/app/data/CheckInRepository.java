package com.streak.app.data;

import com.streak.app.db.CheckInRecordDao;
import com.streak.app.model.CheckInRecord;
import com.streak.app.model.HabitItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 打卡记录仓库（Phase B 从 {@code AppRepository} 拆出）。
 *
 * <p><b>职责边界。</b>打卡的增删改查（打卡/撤销/补卡/备注/心情/耗时/照片），以及打卡照片的清理。
 * 打卡真相源是 {@code check_in_records} 表，本类直接操作 {@link CheckInRecordDao}。</p>
 *
 * <p><b>打卡写入唯一入口。</b>打卡的增删改统一走直写 API {@link #upsertCheckIn}/{@link #removeCheckIn}
 * 直接落记录表——这是打卡数据的<b>唯一</b>写入路径。{@code saveHabit} 只保存习惯元数据、不再回写打卡，
 * 避免编辑页持过期 {@code completedDates} 快照时反向同步误删并发产生的打卡记录。</p>
 *
 * <p><b>聚合桥（读时物化）。</b>历史上 {@code HabitItem} 用 {@code completedDates/notes} 两个内存派生字段
 * 表达打卡，约 90 处消费端仍读它们。本类提供 {@link #aggregateInto} 把记录聚合回填进这两个字段，
 * 供 {@code HabitRepository}/{@code AppRepository} 读习惯时调用，是记录表 -> 过渡视图的只读桥接点。</p>
 *
 * <p><b>回写桥（仅备份恢复用）。</b>{@link #syncFrom} 把 {@code completedDates/notes} 反向同步进记录表，
 * <b>如今只剩 {@code HabitRepository.writeHabits}（整机备份恢复的整表替换）一个调用方</b>——那里的习惯对象
 * 来自刚解析的备份、其派生字段就是权威来源，故用它重建记录表是安全的。日常保存习惯（saveHabit）绝不调它。
 * 该方法不自开事务，由调用方（{@code writeHabits}）用 {@code database.runInTransaction} 与整表替换绑成一笔事务。</p>
 */
public class CheckInRepository {

    private final CheckInRecordDao checkInRecordDao;
    private final ImageStore imageStore;
    private final Predicate<String> imageReferenceChecker;

    public CheckInRepository(CheckInRecordDao checkInRecordDao, ImageStore imageStore) {
        // 没有全表引用检查器时只能采取保守策略，避免误删仍被其它记录引用的文件。
        this(checkInRecordDao, imageStore, uri -> true);
    }

    public CheckInRepository(CheckInRecordDao checkInRecordDao, ImageStore imageStore,
                             Predicate<String> imageReferenceChecker) {
        this.checkInRecordDao = checkInRecordDao;
        this.imageStore = imageStore;
        this.imageReferenceChecker = imageReferenceChecker == null
                ? uri -> true : imageReferenceChecker;
    }

    /** 取某习惯某天的打卡记录（含心情/耗时/照片），无则 null。 */
    public CheckInRecord getCheckIn(long habitId, String date) {
        if (date == null || date.isEmpty()) {
            return null;
        }
        return checkInRecordDao.getByHabitAndDate(habitId, date);
    }

    public CheckInRecord getCheckInForOwner(long habitId, String date, String owner) {
        if (date == null || date.isEmpty() || owner == null || owner.isEmpty()) {
            return null;
        }
        return checkInRecordDao.getByHabitAndDateForOwner(habitId, date, owner);
    }

    /** 取某习惯全部打卡记录，按日期降序（最近在前）。供详情页时间线直接读富字段。 */
    public List<CheckInRecord> getCheckIns(long habitId) {
        List<CheckInRecord> records = checkInRecordDao.getByHabit(habitId);
        if (records == null) {
            return new ArrayList<>();
        }
        Collections.sort(records, (a, b) -> b.getDate().compareTo(a.getDate()));
        return records;
    }

    /** 账号限定、按日期升序取某习惯全部打卡记录（供聚合回填，JOIN owner，同事务内调用）。 */
    public List<CheckInRecord> getCheckInsForOwnerAsc(long habitId, String owner) {
        if (owner == null || owner.isEmpty()) {
            return new ArrayList<>();
        }
        List<CheckInRecord> records = checkInRecordDao.getByHabitForOwner(habitId, owner);
        return records == null ? new ArrayList<>() : records;
    }

    public List<CheckInRecord> getCheckInsForOwner(long habitId, String owner) {
        if (owner == null || owner.isEmpty()) {
            return new ArrayList<>();
        }
        List<CheckInRecord> records = checkInRecordDao.getByHabitForOwner(habitId, owner);
        if (records == null) {
            return new ArrayList<>();
        }
        Collections.sort(records, (a, b) -> b.getDate().compareTo(a.getDate()));
        return records;
    }

    /**
     * 直接写入/覆盖某习惯某天的打卡记录（含心情/耗时/照片/备注）——打卡真相源的直写入口。
     * (habitId,date) 冲突时 {@code @Upsert} 原地更新当天那条。换照片时删除被替换掉的旧照片文件。
     */
    public boolean upsertCheckIn(long habitId, String date, int mood,
                                 int durationMinutes, String note, String photoUri,
                                 String owner) {
        // 6 参遗留签名：无乐观并发信息，无条件覆盖当天记录（沿用旧语义，换图则删旧图）。
        CheckInRecord record = validatedRecord(habitId, date, mood, durationMinutes, note,
                photoUri, owner);
        if (record == null) {
            return false;
        }
        CheckInRecordDao.OwnedUpsertResult result =
                checkInRecordDao.upsertAndReturnPreviousForOwner(record, owner);
        if (!result.wasWritten()) {
            return false;
        }
        CheckInRecord previous = result.getPrevious();
        String oldPhoto = previous == null ? null : previous.getPhotoUri();
        if (oldPhoto != null && !oldPhoto.equals(record.getPhotoUri())) {
            deletePhotoIfUnreferenced(oldPhoto);
        }
        return true;
    }

    /** 校验输入并构造 record；非法返回 null。date 规范 yyyy-MM-dd、mood 0..5、duration 非负。 */
    private CheckInRecord validatedRecord(long habitId, String date, int mood,
                                          int durationMinutes, String note, String photoUri,
                                          String owner) {
        if (owner == null || owner.isEmpty() || !isValidDate(date)) {
            return null;
        }
        if (mood < 0 || mood > 5 || durationMinutes < 0) {
            return null;
        }
        CheckInRecord record = new CheckInRecord();
        record.setHabitId(habitId);
        record.setDate(date);
        record.setMood(mood);
        record.setDurationMinutes(durationMinutes);
        record.setNote(note == null || note.trim().isEmpty() ? null : note.trim());
        record.setPhotoUri(photoUri == null || photoUri.trim().isEmpty() ? null : photoUri.trim());
        return record;
    }

    /**
     * 直写打卡（带输入校验 + 照片乐观并发校验）。
     *
     * <p><b>输入校验（工作项8）。</b>date 必须是规范 {@code yyyy-MM-dd}（含真实存在的日期）、
     * mood 限 {@code 0..5}、durationMinutes 不得为负；任一非法直接拒绝（返回 false），不写库。</p>
     *
     * <p><b>照片乐观并发（工作项4）。</b>photoChanged=false 时保留数据库当前最新照片；
     * photoChanged=true 时要求数据库当前照片仍等于 expectedOriginalPhotoUri，否则原子拒绝，
     * 并由本方法清理调用方本次新拷贝的临时图，杜绝旧快照覆盖并发新图。</p>
     */
    public boolean upsertCheckIn(long habitId, String date, int mood,
                                 int durationMinutes, String note, String photoUri,
                                 String owner, String expectedOriginalPhotoUri,
                                 boolean photoChanged) {
        CheckInRecord record = validatedRecord(habitId, date, mood, durationMinutes, note,
                photoUri, owner);
        if (record == null) {
            return false;
        }
        String newPhoto = record.getPhotoUri();
        // 读旧记录、比对期望原照片、写入，全在同一 Room 事务内，杜绝并发换图的时间窗。
        CheckInRecordDao.OwnedUpsertResult result =
                checkInRecordDao.upsertAndReturnPreviousForOwner(
                        record, owner, expectedOriginalPhotoUri, photoChanged);
        if (!result.wasWritten()) {
            // 被拒（归属不符 / 照片已被并发替换）：清理本次新拷贝的临时图，避免留孤儿文件。
            if (photoChanged && newPhoto != null) {
                deletePhotoIfUnreferenced(newPhoto);
            }
            return false;
        }
        CheckInRecord previous = result.getPrevious();
        String oldPhoto = previous == null ? null : previous.getPhotoUri();
        // record.getPhotoUri() 是本次实际落库的照片（未改照片时已被 DAO 回填为 DB 现值）。
        if (oldPhoto != null && !oldPhoto.equals(record.getPhotoUri())) {
            deletePhotoIfUnreferenced(oldPhoto);
        }
        return true;
    }

    /** 校验 date 是规范的 yyyy-MM-dd 且为真实存在的日历日期。 */
    private static boolean isValidDate(String date) {
        if (date == null || date.length() != 10) {
            return false;
        }
        try {
            java.time.LocalDate parsed = java.time.LocalDate.parse(
                    date, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
            return parsed.toString().equals(date);
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    /**
     * 撤销某习惯某天的打卡：删记录表里的那一条，并删掉其附带的打卡照片文件（避免孤儿图）。
     */
    public void removeCheckIn(long habitId, String date, String owner) {
        if (date == null || date.isEmpty() || owner == null || owner.isEmpty()) {
            return;
        }
        CheckInRecord deleted = checkInRecordDao.deleteAndReturnByHabitAndDateForOwner(
                habitId, date, owner);
        if (deleted != null) {
            deletePhotoIfUnreferenced(deleted.getPhotoUri());
        }
    }

    /**
     * 批量取回给定习惯的打卡记录并按 habitId 分组（消除 N+1）。空列表直接返回空 map，
     * 避免拼出 {@code IN ()} 空集查询。
     */
    public Map<Long, List<CheckInRecord>> recordsGroupedByHabit(List<Long> habitIds) {
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
     * 账号限定的分组取回：JOIN habits 按 owner 过滤一次取回该账号全部打卡记录并按 habitId 分组。
     * 供 HabitRepository 在「按 owner 读习惯」的同一事务里聚合，消除跨查询时间窗与账号越界。
     */
    public Map<Long, List<CheckInRecord>> recordsGroupedByOwner(String owner) {
        Map<Long, List<CheckInRecord>> byHabit = new HashMap<>();
        if (owner == null || owner.isEmpty()) {
            return byHabit;
        }
        List<CheckInRecord> records = checkInRecordDao.getByOwner(owner);
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
     */
    public void aggregateInto(HabitItem habit) {
        if (habit == null) {
            return;
        }
        aggregateInto(habit, checkInRecordDao.getByHabit(habit.getId()));
    }

    /**
     * 用「已取好的记录列表」把打卡数据聚合回填进内存派生字段，避免重复查库（消除 N+1）。
     */
    public void aggregateInto(HabitItem habit, List<CheckInRecord> records) {
        if (habit == null) {
            return;
        }
        List<String> dates = new ArrayList<>();
        Map<String, String> notes = new HashMap<>();
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

    /**
     * 把某习惯内存派生字段（completedDates/notes）的状态同步进 check_in_records 表：
     * 新增缺失日期的记录、删除已不在 completedDates 里的记录、更新备注。
     *
     * <p><b>唯一调用方：{@code HabitRepository.writeHabits}（备份恢复的整表替换）。</b>
     * 恢复时习惯与打卡整体来自同一份备份快照，据 completedDates 重建记录是正确且必要的。</p>
     *
     * <p><b>⚠️ 绝不可用于「编辑习惯」路径（{@code saveHabit}）。</b>编辑页可能持有<b>过期快照</b>
     * ——加载后、保存前若有并发打卡，其 completedDates 已陈旧；此时按它反向 diff 会<b>误删</b>
     * 并发新增的打卡记录。故 {@code saveHabit} 只 upsert 习惯行，打卡增删改一律走直写 API
     * （{@code upsertCheckIn}/{@code removeCheckIn}）。这是本方法与 saveHabit 解耦的根本原因。</p>
     *
     * <p>关键：对仍保留的日期，<b>保留其已有的 mood/duration/photo</b>——只覆盖 note，
     * 按日期 diff 增量同步而非 clear + 重插，避免抹掉心情/耗时/照片。记录表是真相源。</p>
     *
     * <p>不自开事务：调用方（{@code writeHabits}）须把本方法与习惯行替换绑成同一事务。</p>
     */
    public void syncFrom(HabitItem habit) {
        if (habit == null) {
            return;
        }
        long habitId = habit.getId();
        List<CheckInRecord> existing = checkInRecordDao.getByHabit(habitId);
        Map<String, CheckInRecord> existingByDate = new HashMap<>();
        if (existing != null) {
            for (CheckInRecord record : existing) {
                existingByDate.put(record.getDate(), record);
            }
        }

        Set<String> targetDates = new LinkedHashSet<>();
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

    List<String> getCheckInPhotoUris(long habitId) {
        List<String> photoUris = new ArrayList<>();
        List<CheckInRecord> records = checkInRecordDao.getByHabit(habitId);
        if (records == null) {
            return photoUris;
        }
        for (CheckInRecord record : records) {
            if (record.getPhotoUri() != null && !record.getPhotoUri().trim().isEmpty()) {
                photoUris.add(record.getPhotoUri());
            }
        }
        return photoUris;
    }

    void deletePhotos(List<String> photoUris) {
        if (photoUris == null) {
            return;
        }
        for (String photoUri : photoUris) {
            deletePhotoIfUnreferenced(photoUri);
        }
    }

    /** 供门面在数据库提交后清理临时/孤儿图片；仍统一经过全表引用检查。 */
    public void deletePhotoIfUnreferenced(String photoUri) {
        deletePhotoIfUnreferencedInternal(photoUri);
    }

    private void deletePhotoIfUnreferencedInternal(String photoUri) {
        if (photoUri == null || photoUri.trim().isEmpty()) {
            return;
        }
        try {
            if (imageReferenceChecker.test(photoUri)) {
                return;
            }
        } catch (RuntimeException ignored) {
            // 引用检查失败时宁可留下文件，也不能误删仍被数据库引用的图片。
            return;
        }
        imageStore.deletePhoto(photoUri);
    }
}
