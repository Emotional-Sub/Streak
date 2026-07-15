package com.streak.app.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.streak.app.model.CheckInRecord;

import java.util.List;

/**
 * 打卡记录表数据访问对象。同步阻塞调用，线程调度由上层负责（同 {@link HabitDao}）。
 *
 * <p>业务唯一性靠 {@code (habitId, date)} 唯一索引：同一天再次插入按 {@code REPLACE} 覆盖，
 * 承接既有「一天一条打卡」的口径。跨习惯的引用完整性由 {@link com.streak.app.storage.AppRepository}
 * 维护（删习惯/整表替换时显式清理对应记录），不依赖外键级联。</p>
 */
@Dao
public interface CheckInRecordDao {

    @Query("SELECT * FROM check_in_records")
    List<CheckInRecord> getAll();

    /** 某习惯的全部打卡记录，按日期升序。 */
    @Query("SELECT * FROM check_in_records WHERE habitId = :habitId ORDER BY date ASC")
    List<CheckInRecord> getByHabit(long habitId);

    /** 取某习惯某天的记录（唯一），无则 null。 */
    @Query("SELECT * FROM check_in_records WHERE habitId = :habitId AND date = :date LIMIT 1")
    CheckInRecord getByHabitAndDate(long habitId, String date);

    @Query("SELECT COUNT(*) FROM check_in_records")
    int count();

    /**
     * upsert 单条：靠 (habitId, date) 唯一索引冲突时 REPLACE，即「同一天覆盖」。
     * 注意 REPLACE 会换新自增主键 id——业务上不依赖记录 id 的稳定性，可接受。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(CheckInRecord record);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<CheckInRecord> records);

    /** 删某习惯某天的打卡（撤销打卡用）。 */
    @Query("DELETE FROM check_in_records WHERE habitId = :habitId AND date = :date")
    void deleteByHabitAndDate(long habitId, String date);

    /** 删某习惯的全部打卡记录（删习惯时用，替代外键级联）。 */
    @Query("DELETE FROM check_in_records WHERE habitId = :habitId")
    void deleteByHabit(long habitId);

    /** 删某账号名下所有习惯的打卡记录（删号时用）。 */
    @Query("DELETE FROM check_in_records WHERE habitId IN "
            + "(SELECT id FROM habits WHERE ownerUsername = :owner)")
    void deleteByOwner(String owner);

    @Query("DELETE FROM check_in_records")
    void clear();
}
