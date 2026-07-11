package com.streak.app.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

import com.streak.app.model.HabitItem;
import com.streak.app.model.UserAccount;

/**
 * 应用本地数据库（Room）。用 SQLite 持久化习惯与账号两张表，替代旧的 Gson JSON 文件存储。
 *
 * <p>设计取舍：习惯里的 tags/completedDates（List）与 notes（Map）用 {@link Converters}
 * 序列化成 JSON 字符串存单列——数据量小、查询不需要按这些字段过滤，扁平化成关联表反而增加复杂度。</p>
 *
 * <p>单例：整个进程共享一个连接池，避免多实例导致的锁竞争与内存浪费。</p>
 */
@Database(
        entities = {HabitItem.class, UserAccount.class},
        version = 1,
        exportSchema = false
)
@TypeConverters(Converters.class)
public abstract class StreakDatabase extends RoomDatabase {

    public abstract HabitDao habitDao();

    public abstract UserDao userDao();

    private static volatile StreakDatabase instance;

    public static StreakDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (StreakDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    StreakDatabase.class,
                                    "streak.db")
                            // 迁移策略：当前只有 v1；后续升表结构时再补 addMigrations。
                            // 旧 JSON 数据的搬运由 AppRepository 在首启时完成，不走 Room 迁移。
                            //
                            // 允许主线程查询：旧 JSON 存储本就是同步调用（StreakApp 冷启动、
                            // 各 Activity onCreate 都在主线程直接读），个人习惯 App 数据量极小
                            // （几十条习惯、账号个位数），主线程读可接受。写操作上层已在后台线程。
                            // 这样保持既有同步语义，无需重写 30 个文件的线程模型。
                            .allowMainThreadQueries()
                            .build();
                }
            }
        }
        return instance;
    }
}
