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

/**
 * 打卡记录仓库（Phase B 从 {@code AppRepository} 拆出）。
 *
 * <p><b>职责边界。</b>打卡的增删改查（打卡/撤销/补卡/备注/心情/耗时/照片），以及打卡照片的清理。
 * 打卡真相源是 {@code check_in_records} 表，本类直接操作 {@link CheckInRecordDao}。</p>
 *
 * <p><b>聚合桥。</b>历史上 {@code HabitItem} 用 {@code completedDates/notes} 两个内存派生字段
 * 表达打卡，约 90 处消费端仍读它们。本类提供 {@link #aggregateInto} 把记录聚合回填进这两个字段
 * （读时物化）、{@link #syncFrom} 把这两个字段的变化写回记录表（旧写入路径的回写桥）。
 * 这两个方法供 {@code HabitRepository}/{@code AppRepository} 在读写习惯时调用，
 * 是记录表与过渡视图之间的唯一桥接点。</p>
 *
 * <p><b>事务边界。</b>{@link #syncFrom} 与删习惯行必须与之绑成同一事务，故本类不自开事务，
 * 由调用方（{@code AppRepository.saveHabit}）用 {@code database.runInTransaction} 包住。</p>
 */
public class CheckInRepository {

    private final CheckInRecordDao checkInRecordDao;
    private final ImageStore imageStore;

    public CheckInRepository(CheckInRecordDao checkInRecordDao, ImageStore imageStore) {
        this.checkInRecordDao = checkInRecordDao;
        this.imageStore = imageStore;
    }

    /** 取某习惯某天的打卡记录（含心情/耗时/照片），无则 null。 */
    public CheckInRecord getCheckIn(long habitId, String date) {
        if (date == null || date.isEmpty()) {
            return null;
        }
        return checkInRecordDao.getByHabitAndDate(habitId, date);
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

    /**
     * 直接写入/覆盖某习惯某天的打卡记录（含心情/耗时/照片/备注）——打卡真相源的直写入口。
     * (habitId,date) 冲突时 {@code @Upsert} 原地更新当天那条。换照片时删除被替换掉的旧照片文件。
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
            imageStore.deletePhoto(oldPhoto);
        }
    }

    /**
     * 撤销某习惯某天的打卡：删记录表里的那一条，并删掉其附带的打卡照片文件（避免孤儿图）。
     */
    public void removeCheckIn(long habitId, String date) {
        if (date == null || date.isEmpty()) {
            return;
        }
        CheckInRecord existing = checkInRecordDao.getByHabitAndDate(habitId, date);
        if (existing != null) {
            imageStore.deletePhoto(existing.getPhotoUri());
        }
        checkInRecordDao.deleteByHabitAndDate(habitId, date);
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
     * <p>关键：对仍保留的日期，<b>保留其已有的 mood/duration/photo</b>——既有打卡 UI 只改
     * 日期与备注，若这里整表重建会把心情/耗时/照片抹掉。故按日期 diff 增量同步，
     * 而非 clear + 重插。记录表是真相源，本方法是「旧视图字段 -> 真相源」的回写桥。</p>
     *
     * <p>不自开事务：调用方须把本方法与习惯行 upsert 绑成同一事务。</p>
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

    /**
     * 删除某习惯所有打卡记录里附带的照片文件（每天打卡可各自配一张，与习惯自身 imageUri 独立）。
     * 删习惯/删号时调用，避免记录行删了但磁盘照片成孤儿文件。
     */
    public void deleteCheckInPhotos(long habitId) {
        List<CheckInRecord> records = checkInRecordDao.getByHabit(habitId);
        if (records == null) {
            return;
        }
        for (CheckInRecord record : records) {
            imageStore.deletePhoto(record.getPhotoUri());
        }
    }
}
