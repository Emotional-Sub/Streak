package com.streak.app.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.streak.app.model.CheckInRecord;
import com.streak.app.model.HabitItem;
import com.streak.app.model.UserAccount;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用本地数据库（Room）。用 SQLite 持久化习惯、账号、打卡记录三张表，替代旧的 Gson JSON 文件存储。
 *
 * <p>设计取舍：习惯里的 tags（List）用 {@link Converters} 序列化成一列 JSON——数据量小、
 * 不需要按标签过滤。打卡记录早期也塞在 habits 的 completedDates/notes 两列 JSON 里，v2->v3
 * 已拆成规范化的 {@link CheckInRecord}（habits 1:N check_in_records），以便 SQL 统计、
 * (habitId,date) 唯一约束及扩展心情/耗时/照片。</p>
 *
 * <p>单例：整个进程共享一个连接池，避免多实例导致的锁竞争与内存浪费。</p>
 */
@Database(
        entities = {HabitItem.class, UserAccount.class, CheckInRecord.class},
        version = 4,
        exportSchema = true
)
@TypeConverters(Converters.class)
public abstract class StreakDatabase extends RoomDatabase {

    public abstract HabitDao habitDao();

    public abstract UserDao userDao();

    public abstract CheckInRecordDao checkInRecordDao();

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

    /**
     * v2 -> v3：把塞在 habits.completedDates（JSON 数组）/ notes（JSON 对象）里的打卡数据
     * 拆进规范化的 check_in_records 表，再把这两列从 habits 物理移除。
     *
     * <p>步骤（顺序不能乱，尤其「先回填、后删列」）：</p>
     * <ol>
     *   <li>建 check_in_records 表 + 两个索引，DDL 必须与 Room 依 {@link CheckInRecord} 生成的
     *       schema 完全一致（列亲和性/NOT NULL/AUTOINCREMENT 主键/自动索引名），否则 Room 打开时
     *       校验 TableInfo 不匹配会抛异常。</li>
     *   <li>逐行读旧 habits 的 completedDates/notes，在 Java 里用 Gson 解析（SQLite JSON1 在
     *       API 26 上不保证可用），为每个打卡日期插一条记录，note 取自 notes 里的同日期项；
     *       mood/duration/photo 旧数据没有，留 0/null。只在 notes 里、却不在 completedDates 里的
     *       「孤儿备注」历来从不展示（UI 只对已打卡日显示备注），故不迁移。</li>
     *   <li>SQLite（API 26）不支持 DROP COLUMN：建新 habits 表（去掉两列）→ 拷数据 →
     *       删旧表 → 改名，完成列的物理移除。新表 DDL 同样要匹配去列后的 HabitItem。</li>
     * </ol>
     */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // 1) 新表 + 索引（DDL 与 Room 生成物逐字对齐）
            database.execSQL("CREATE TABLE IF NOT EXISTS `check_in_records` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`habitId` INTEGER NOT NULL, "
                    + "`date` TEXT NOT NULL, "
                    + "`note` TEXT, "
                    + "`mood` INTEGER NOT NULL, "
                    + "`durationMinutes` INTEGER NOT NULL, "
                    + "`photoUri` TEXT)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS "
                    + "`index_check_in_records_habitId_date` ON `check_in_records` (`habitId`, `date`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS "
                    + "`index_check_in_records_habitId` ON `check_in_records` (`habitId`)");

            // 2) 回填：解析旧 completedDates/notes -> 每个打卡日一条记录
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.lang.reflect.Type listType =
                    new com.google.gson.reflect.TypeToken<java.util.List<String>>() {}.getType();
            java.lang.reflect.Type mapType =
                    new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType();

            android.database.Cursor cursor = database.query(
                    "SELECT id, completedDates, notes FROM habits");
            try {
                int idIdx = cursor.getColumnIndexOrThrow("id");
                int datesIdx = cursor.getColumnIndexOrThrow("completedDates");
                int notesIdx = cursor.getColumnIndexOrThrow("notes");
                while (cursor.moveToNext()) {
                    long habitId = cursor.getLong(idIdx);
                    String datesJson = cursor.isNull(datesIdx) ? null : cursor.getString(datesIdx);
                    String notesJson = cursor.isNull(notesIdx) ? null : cursor.getString(notesIdx);

                    List<String> dates = parseList(gson, listType, datesJson);
                    Map<String, String> notes = parseMap(gson, mapType, notesJson);
                    if (dates.isEmpty()) {
                        continue;
                    }
                    // 去重：同一天只留一条（承接既有 HashSet 去重口径）
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    for (String date : dates) {
                        if (date == null || date.isEmpty() || !seen.add(date)) {
                            continue;
                        }
                        String note = notes.get(date);
                        database.execSQL(
                                "INSERT OR IGNORE INTO `check_in_records` "
                                        + "(habitId, date, note, mood, durationMinutes, photoUri) "
                                        + "VALUES (?, ?, ?, 0, 0, NULL)",
                                new Object[]{habitId, date, note});
                    }
                }
            } finally {
                cursor.close();
            }

            // 3) 物理移除 habits.completedDates / notes：建新表 -> 拷数据 -> 换名
            database.execSQL("CREATE TABLE `habits_new` ("
                    + "`id` INTEGER PRIMARY KEY NOT NULL, "
                    + "`title` TEXT, `content` TEXT, `reminderTime` TEXT, `createdAt` TEXT, "
                    + "`imageUri` TEXT, `category` TEXT, `tags` TEXT, "
                    + "`reminderEnabled` INTEGER NOT NULL, "
                    + "`weeklyTarget` INTEGER NOT NULL, "
                    + "`ownerUsername` TEXT)");
            database.execSQL("INSERT INTO `habits_new` "
                    + "(id, title, content, reminderTime, createdAt, imageUri, category, tags, "
                    + "reminderEnabled, weeklyTarget, ownerUsername) "
                    + "SELECT id, title, content, reminderTime, createdAt, imageUri, category, tags, "
                    + "reminderEnabled, weeklyTarget, ownerUsername FROM `habits`");
            database.execSQL("DROP TABLE `habits`");
            database.execSQL("ALTER TABLE `habits_new` RENAME TO `habits`");
        }

        private List<String> parseList(com.google.gson.Gson gson,
                                       java.lang.reflect.Type type, String json) {
            if (json == null || json.isEmpty()) {
                return new java.util.ArrayList<>();
            }
            try {
                List<String> list = gson.fromJson(json, type);
                return list == null ? new java.util.ArrayList<>() : list;
            } catch (Exception e) {
                return new java.util.ArrayList<>();
            }
        }

        private Map<String, String> parseMap(com.google.gson.Gson gson,
                                             java.lang.reflect.Type type, String json) {
            if (json == null || json.isEmpty()) {
                return new HashMap<>();
            }
            try {
                Map<String, String> map = gson.fromJson(json, type);
                return map == null ? new HashMap<>() : map;
            } catch (Exception e) {
                return new HashMap<>();
            }
        }
    };

    /**
     * v3 -> v4：给 check_in_records 加真正的外键 habitId -> habits.id ON DELETE CASCADE。
     *
     * <p>动机：v3 时 Habit 的 upsert 用 {@code @Insert(REPLACE)}（先删后插），若那时加 FK CASCADE，
     * 每次编辑/打卡习惯的 upsert 都会级联删光其打卡记录，故当时不加 FK、由 Repository 手动清理。
     * v4 起两表 upsert 都改用 Room {@code @Upsert}（先查后 INSERT/UPDATE，主键行不被删除），
     * CASCADE 不再被日常 upsert 误触发，遂补上外键，把「删习惯连带删记录」交给数据库自动完成。</p>
     *
     * <p>SQLite 无法给已存在的表 ALTER ADD 外键，只能重建：建带 FK 的新表 -> 拷数据 ->
     * 删旧表 -> 改名 -> 重建两个索引。新表 DDL 必须与 Room 依 {@link CheckInRecord}（带 @ForeignKey）
     * 生成的 schema 完全一致，否则打开时 TableInfo 校验不匹配会抛异常。</p>
     *
     * <p>不手动切 {@code PRAGMA foreign_keys}：Room 在事务中执行 migrate()，而该 PRAGMA
     * 在事务内是 no-op。重建期间不会触发约束违反，因为拷数据时只搬仍有对应 habits 行的记录
     * （{@code WHERE habitId IN (SELECT id FROM habits)}），顺带清掉历史孤儿记录。</p>
     */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // 建带 FK 的新表（DDL 与 Room 生成物逐字对齐：FK 子句 + 列定义顺序/亲和性）
            database.execSQL("CREATE TABLE IF NOT EXISTS `check_in_records_new` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`habitId` INTEGER NOT NULL, "
                    + "`date` TEXT NOT NULL, "
                    + "`note` TEXT, "
                    + "`mood` INTEGER NOT NULL, "
                    + "`durationMinutes` INTEGER NOT NULL, "
                    + "`photoUri` TEXT, "
                    + "FOREIGN KEY(`habitId`) REFERENCES `habits`(`id`) "
                    + "ON UPDATE NO ACTION ON DELETE CASCADE)");
            // 拷数据：只搬仍有对应 habits 行的记录，顺手清理历史遗留的孤儿记录
            //（否则新表建了 FK 后，孤儿记录会违反约束）。
            database.execSQL("INSERT INTO `check_in_records_new` "
                    + "(id, habitId, date, note, mood, durationMinutes, photoUri) "
                    + "SELECT id, habitId, date, note, mood, durationMinutes, photoUri "
                    + "FROM `check_in_records` "
                    + "WHERE habitId IN (SELECT id FROM habits)");
            database.execSQL("DROP TABLE `check_in_records`");
            database.execSQL("ALTER TABLE `check_in_records_new` RENAME TO `check_in_records`");
            // 重建两个索引（表重建后索引不会自动跟随）
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS "
                    + "`index_check_in_records_habitId_date` ON `check_in_records` (`habitId`, `date`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS "
                    + "`index_check_in_records_habitId` ON `check_in_records` (`habitId`)");
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
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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

    /**
     * 仅供单测使用：关闭并清空单例，使下一次 getInstance() 重建全新数据库。
     * Robolectric 不会在测试方法间重置静态字段，若不清空，前一用例写入的数据会串到后一用例。
     * 生产代码不应调用。
     */
    @androidx.annotation.VisibleForTesting
    public static void resetForTest() {
        synchronized (StreakDatabase.class) {
            if (instance != null && instance.isOpen()) {
                instance.close();
            }
            instance = null;
        }
    }
}
