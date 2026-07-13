package com.streak.app.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

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
        version = 2,
        exportSchema = false
)
@TypeConverters(Converters.class)
public abstract class StreakDatabase extends RoomDatabase {

    public abstract HabitDao habitDao();

    public abstract UserDao userDao();

    /**
     * v1 -> v2：习惯表加 ownerUsername 列以支持每账号数据隔离。
     * 存量习惯（升级前是全局共享的）统一归给演示账号 student，
     * 与首启种子习惯的归属保持一致，避免升级后老用户的习惯突然「无主」而查不出来。
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE habits ADD COLUMN ownerUsername TEXT");
            database.execSQL("UPDATE habits SET ownerUsername = 'student' WHERE ownerUsername IS NULL");
        }
    };

    private static volatile StreakDatabase instance;

    public static StreakDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (StreakDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    StreakDatabase.class,
                                    "streak.db")
                            .addMigrations(MIGRATION_1_2)
                            // 迁移策略：v1->v2 给 habits 加 ownerUsername 列并把存量习惯归属演示账号。
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
