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

    @Query("SELECT * FROM habits WHERE id = :id LIMIT 1")
    HabitItem findById(long id);

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
