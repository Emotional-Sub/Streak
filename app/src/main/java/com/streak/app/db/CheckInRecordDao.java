package com.streak.app.db;

import androidx.room.Dao;
import androidx.room.Query;
import androidx.room.Upsert;

import com.streak.app.model.CheckInRecord;

import java.util.List;

/**
 * 打卡记录表数据访问对象。同步阻塞调用，线程调度由上层负责（同 {@link HabitDao}）。
 *
 * <p>业务唯一性靠 {@code (habitId, date)} 唯一索引：同一天再次 upsert 按唯一冲突更新当天那条，
 * 承接既有「一天一条打卡」的口径。跨习惯的引用完整性 v4 起由数据库的 FK ON DELETE CASCADE
 * 保证（删习惯自动删其记录），Repository 不必再手动清理。</p>
 *
 * <p><b>为什么用 {@code @Upsert}。</b>{@code @Insert(REPLACE)} 冲突时「先删后插」，会换掉自增主键；
 * {@code @Upsert} 先查后 INSERT/UPDATE，命中 (habitId,date) 时原地更新、保留主键，语义更贴合
 * 「覆盖当天记录」。</p>
 */
@Dao
public interface CheckInRecordDao {

    @Query("SELECT * FROM check_in_records")
    List<CheckInRecord> getAll();

    /** 某习惯的全部打卡记录，按日期升序。 */
    @Query("SELECT * FROM check_in_records WHERE habitId = :habitId ORDER BY date ASC")
    List<CheckInRecord> getByHabit(long habitId);

    /**
     * 批量取多个习惯的打卡记录（消除 N+1）：readHabits 一次取全部记录后在内存按 habitId 分组，
     * 取代「读出习惯再逐个 getByHabit」的多次查询。空列表返回空结果。
     */
    @Query("SELECT * FROM check_in_records WHERE habitId IN (:habitIds) ORDER BY habitId ASC, date ASC")
    List<CheckInRecord> getByHabits(List<Long> habitIds);

    /** 取某习惯某天的记录（唯一），无则 null。 */
    @Query("SELECT * FROM check_in_records WHERE habitId = :habitId AND date = :date LIMIT 1")
    CheckInRecord getByHabitAndDate(long habitId, String date);

    @Query("SELECT COUNT(*) FROM check_in_records")
    int count();

    /**
     * upsert 单条：命中 (habitId, date) 唯一约束时原地 UPDATE 当天那条，否则 INSERT，
     * 即「同一天覆盖」。{@code @Upsert} 保留已有主键，不像 REPLACE 那样先删后插换新 id。
     */
    @Upsert
    void upsert(CheckInRecord record);

    @Upsert
    void upsertAll(List<CheckInRecord> records);

    /** 删某习惯某天的打卡（撤销打卡用）。 */
    @Query("DELETE FROM check_in_records WHERE habitId = :habitId AND date = :date")
    void deleteByHabitAndDate(long habitId, String date);

    /**
     * 删某习惯的全部打卡记录。v4 起删习惯已由 FK CASCADE 自动清理，本方法保留供
     * 过渡期的整表替换/导入路径（先清空再写）等非「删父行」场景显式使用。
     */
    @Query("DELETE FROM check_in_records WHERE habitId = :habitId")
    void deleteByHabit(long habitId);

    /** 删某账号名下所有习惯的打卡记录（过渡期显式清理用；删号删习惯行时 CASCADE 亦会清理）。 */
    @Query("DELETE FROM check_in_records WHERE habitId IN "
            + "(SELECT id FROM habits WHERE ownerUsername = :owner)")
    void deleteByOwner(String owner);

    @Query("DELETE FROM check_in_records")
    void clear();
}
