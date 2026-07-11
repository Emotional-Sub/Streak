package com.streak.app.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.streak.app.model.UserAccount;

import java.util.List;

/**
 * 账号表数据访问对象。同步阻塞调用，线程调度由上层负责（同 {@link HabitDao}）。
 */
@Dao
public interface UserDao {

    @Query("SELECT * FROM accounts")
    List<UserAccount> getAll();

    @Query("SELECT * FROM accounts WHERE username = :username LIMIT 1")
    UserAccount findByUsername(String username);

    @Query("SELECT COUNT(*) FROM accounts")
    int count();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(UserAccount account);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<UserAccount> accounts);

    @Query("DELETE FROM accounts")
    void clear();

    /**
     * 用给定列表整体替换账号表：清空后批量写入，事务保证一致性。
     */
    @androidx.room.Transaction
    default void replaceAll(List<UserAccount> accounts) {
        clear();
        if (accounts != null && !accounts.isEmpty()) {
            upsertAll(accounts);
        }
    }
}
