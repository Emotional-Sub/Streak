package com.streak.app.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 已发布的 Room 迁移 {@link StreakDatabase#MIGRATION_3_4} 的单测（Robolectric + 真实 SQLite）。
 *
 * <p>v3->v4 给 check_in_records 补上真正的外键 habitId -> habits.id ON DELETE CASCADE
 * （配合两表 upsert 改用 {@code @Upsert}，日常编辑/打卡不再误触发级联）。本测建一个 v3 结构
 * （check_in_records 无 FK）的库，插入含孤儿记录的存量数据，手工调用 migrate()，断言：</p>
 * <ul>
 *   <li>迁移后 check_in_records 建立了指向 habits 的外键；</li>
 *   <li>删习惯时其打卡记录被数据库自动级联删除（无需 Repository 手动清理）；</li>
 *   <li>迁移不丢正常数据，且顺带清理没有对应 habits 行的历史孤儿记录。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class Migration3To4Test {

    /** 建一个 v3 结构（habits 去列后 + check_in_records 无 FK）的内存库。 */
    private SupportSQLiteDatabase openV3Database() {
        Context context = ApplicationProvider.getApplicationContext();
        SupportSQLiteOpenHelper.Callback callback = new SupportSQLiteOpenHelper.Callback(3) {
            @Override
            public void onCreate(SupportSQLiteDatabase db) {
                // v3 habits：已物理移除 completedDates/notes 两列（对应 MIGRATION_2_3 之后的形态）。
                db.execSQL("CREATE TABLE `habits` ("
                        + "`id` INTEGER PRIMARY KEY NOT NULL, "
                        + "`title` TEXT, `content` TEXT, `reminderTime` TEXT, `createdAt` TEXT, "
                        + "`imageUri` TEXT, `category` TEXT, `tags` TEXT, "
                        + "`reminderEnabled` INTEGER NOT NULL, "
                        + "`weeklyTarget` INTEGER NOT NULL, "
                        + "`ownerUsername` TEXT)");
                // v3 check_in_records：无外键（当时靠 Repository 手动维护引用完整性）。
                db.execSQL("CREATE TABLE IF NOT EXISTS `check_in_records` ("
                        + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                        + "`habitId` INTEGER NOT NULL, "
                        + "`date` TEXT NOT NULL, "
                        + "`note` TEXT, "
                        + "`mood` INTEGER NOT NULL, "
                        + "`durationMinutes` INTEGER NOT NULL, "
                        + "`photoUri` TEXT)");
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS "
                        + "`index_check_in_records_habitId_date` ON `check_in_records` (`habitId`, `date`)");
                db.execSQL("CREATE INDEX IF NOT EXISTS "
                        + "`index_check_in_records_habitId` ON `check_in_records` (`habitId`)");
            }

            @Override
            public void onUpgrade(SupportSQLiteDatabase db, int oldVersion, int newVersion) {
                // 测试里手工调用迁移，这里不需要实现
            }
        };
        SupportSQLiteOpenHelper.Configuration config =
                SupportSQLiteOpenHelper.Configuration.builder(context)
                        .name(null) // 内存库，用例间互不干扰
                        .callback(callback)
                        .build();
        return new FrameworkSQLiteOpenHelperFactory().create(config).getWritableDatabase();
    }

    private ContentValues v3Habit(long id, String title) {
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("title", title);
        cv.put("content", "内容");
        cv.put("reminderTime", "20:00");
        cv.put("createdAt", "2026-01-01 10:00");
        cv.put("category", "学习");
        cv.put("tags", "[]");
        cv.put("reminderEnabled", 1);
        cv.put("weeklyTarget", 0);
        cv.put("ownerUsername", "student");
        return cv;
    }

    private ContentValues record(long habitId, String date, int mood, int duration, String photo) {
        ContentValues cv = new ContentValues();
        cv.put("habitId", habitId);
        cv.put("date", date);
        cv.put("mood", mood);
        cv.put("durationMinutes", duration);
        cv.put("photoUri", photo);
        return cv;
    }

    @Test
    public void migration_3_4_establishesForeignKey() throws Exception {
        SupportSQLiteDatabase db = openV3Database();
        db.insert("habits", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, v3Habit(3001L, "晨跑"));

        StreakDatabase.MIGRATION_3_4.migrate(db);

        // check_in_records 现在应有一条指向 habits 的外键
        Cursor c = db.query("PRAGMA foreign_key_list(`check_in_records`)");
        boolean hasFk = false;
        while (c.moveToNext()) {
            String table = c.getString(c.getColumnIndexOrThrow("table"));
            String from = c.getString(c.getColumnIndexOrThrow("from"));
            String to = c.getString(c.getColumnIndexOrThrow("to"));
            String onDelete = c.getString(c.getColumnIndexOrThrow("on_delete"));
            if ("habits".equals(table) && "habitId".equals(from) && "id".equals(to)) {
                hasFk = true;
                assertEquals("应为 ON DELETE CASCADE", "CASCADE", onDelete);
            }
        }
        c.close();
        assertTrue("迁移后应建立 habitId->habits.id 外键", hasFk);
        db.close();
    }

    @Test
    public void migration_3_4_cascadeDeletesRecordsOnHabitDelete() throws Exception {
        SupportSQLiteDatabase db = openV3Database();
        db.insert("habits", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, v3Habit(3002L, "背单词"));
        db.insert("check_in_records", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                record(3002L, "2026-01-01", 0, 0, null));
        db.insert("check_in_records", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                record(3002L, "2026-01-02", 0, 0, null));

        StreakDatabase.MIGRATION_3_4.migrate(db);

        // 外键约束需显式打开（迁移完成后由 Room 打开库时开启；测试里手动开）
        db.execSQL("PRAGMA foreign_keys=ON");
        // 删习惯：其打卡记录应被 CASCADE 自动删除
        db.execSQL("DELETE FROM habits WHERE id = 3002");

        Cursor c = db.query("SELECT COUNT(*) FROM check_in_records WHERE habitId = 3002");
        assertTrue(c.moveToFirst());
        assertEquals("删习惯应级联删其记录", 0, c.getInt(0));
        c.close();
        db.close();
    }

    @Test
    public void migration_3_4_preservesDataAndDropsOrphans() throws Exception {
        SupportSQLiteDatabase db = openV3Database();
        db.insert("habits", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, v3Habit(3003L, "冥想"));
        // 正常记录（含富字段）：迁移后应完整保留
        db.insert("check_in_records", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                record(3003L, "2026-01-01", 5, 20, "file:///photo/a.jpg"));
        // 孤儿记录：habitId 无对应 habits 行，迁移建 FK 后应被清理（否则违反约束）
        db.insert("check_in_records", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                record(9999L, "2026-01-01", 0, 0, null));

        StreakDatabase.MIGRATION_3_4.migrate(db);

        // 正常记录保留且富字段不丢
        Cursor c = db.query("SELECT mood, durationMinutes, photoUri "
                + "FROM check_in_records WHERE habitId = 3003 AND date = '2026-01-01'");
        assertTrue(c.moveToFirst());
        assertEquals("心情应保留", 5, c.getInt(0));
        assertEquals("耗时应保留", 20, c.getInt(1));
        assertEquals("照片应保留", "file:///photo/a.jpg", c.getString(2));
        c.close();
        // 孤儿记录被清理
        c = db.query("SELECT COUNT(*) FROM check_in_records WHERE habitId = 9999");
        assertTrue(c.moveToFirst());
        assertEquals("孤儿记录应被清理", 0, c.getInt(0));
        c.close();
        // 总数只剩正常那条
        c = db.query("SELECT COUNT(*) FROM check_in_records");
        assertTrue(c.moveToFirst());
        assertEquals(1, c.getInt(0));
        c.close();
        db.close();
    }

    @Test
    public void migration_3_4_isRunnableOnEmptyDatabase() throws Exception {
        SupportSQLiteDatabase db = openV3Database();
        StreakDatabase.MIGRATION_3_4.migrate(db);
        Cursor c = db.query("SELECT COUNT(*) FROM check_in_records");
        assertTrue(c.moveToFirst());
        assertEquals(0, c.getInt(0));
        c.close();
        db.close();
    }
}
