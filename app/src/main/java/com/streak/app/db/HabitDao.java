package com.streak.app.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.streak.app.model.HabitItem;

import java.util.List;

/**
 * 习惯表数据访问对象。所有方法均为同步阻塞调用——保持与旧 JSON 存储相同的调用语义，
 * 由上层 AppRepository / 各 Activity 负责在后台线程执行（沿用既有的线程模型）。
 */
@Dao
public interface HabitDao {

    @Query("SELECT * FROM habits")
    List<HabitItem> getAll();

    /** 只取某账号的习惯（数据隔离）。 */
    @Query("SELECT * FROM habits WHERE ownerUsername = :owner")
    List<HabitItem> getByOwner(String owner);

    @Query("SELECT * FROM habits WHERE id = :id LIMIT 1")
    HabitItem findById(long id);

    /**
     * 全表判断某 id 是否已被占用（跨所有账号）。
     * id 是全表主键，生成新 id 时必须对整表防撞——只在当前账号内查会漏掉其它账号的
     * 同 id 习惯，导致 upsert 的 REPLACE 跨账号覆盖，破坏数据隔离。
     */
    @Query("SELECT EXISTS(SELECT 1 FROM habits WHERE id = :id)")
    boolean existsById(long id);

    @Query("SELECT COUNT(*) FROM habits")
    int count();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(HabitItem habit);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<HabitItem> habits);

    @Query("DELETE FROM habits WHERE id = :id")
    void deleteById(long id);

    @Query("DELETE FROM habits")
    void clear();

    /** 只清空某账号的习惯（删号时用，不动其它账号数据）。 */
    @Query("DELETE FROM habits WHERE ownerUsername = :owner")
    void clearByOwner(String owner);

    /**
     * 用给定列表整体替换某账号的习惯：先删该账号旧数据，再批量写入。
     * 事务性保证中途失败回滚，且绝不触碰其它账号的习惯。
     */
    @androidx.room.Transaction
    default void replaceAllForOwner(String owner, List<HabitItem> habits) {
        clearByOwner(owner);
        if (habits != null && !habits.isEmpty()) {
            upsertAll(habits);
        }
    }

    /**
     * 用给定列表整体替换习惯表：清空后批量写入。
     * 事务性由 {@link androidx.room.Transaction} 保证——中途失败会回滚，
     * 不会出现「清空了但没写回」的数据丢失。
     */
    @androidx.room.Transaction
    default void replaceAll(List<HabitItem> habits) {
        clear();
        if (habits != null && !habits.isEmpty()) {
            upsertAll(habits);
        }
    }
}
