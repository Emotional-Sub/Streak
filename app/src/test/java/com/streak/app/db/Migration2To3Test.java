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
 * 已发布的 Room 迁移 {@link StreakDatabase#MIGRATION_2_3} 的单测（Robolectric + 真实 SQLite）。
 *
 * <p>v2->v3 把塞在 habits.completedDates（JSON 数组）/ notes（JSON 对象）里的打卡数据
 * 拆进规范化的 check_in_records 表，并把这两列从 habits 物理移除。本测直接建一个 v2 结构的
 * habits 表、插入带打卡 JSON 的存量数据，手工调用 migrate()，断言：</p>
 * <ul>
 *   <li>每个打卡日期迁出一条 check_in_records 记录，备注落到对应日期；</li>
 *   <li>同一天重复日期按去重口径只留一条（承接既有 HashSet 去重）；</li>
 *   <li>habits 表迁移后不再有 completedDates/notes 两列，且不丢行、不改其它字段。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class Migration2To3Test {

    /** 建一个 v2（有 ownerUsername，仍含 completedDates/notes 两列）的内存 habits 表。 */
    private SupportSQLiteDatabase openV2Database() {
        Context context = ApplicationProvider.getApplicationContext();
        SupportSQLiteOpenHelper.Callback callback = new SupportSQLiteOpenHelper.Callback(2) {
            @Override
            public void onCreate(SupportSQLiteDatabase db) {
                // v2 habits 表：v1 结构 + ownerUsername 列（对应 MIGRATION_1_2 之后的形态）。
                db.execSQL("CREATE TABLE habits ("
                        + "id INTEGER PRIMARY KEY NOT NULL, "
                        + "title TEXT, content TEXT, reminderTime TEXT, createdAt TEXT, "
                        + "imageUri TEXT, category TEXT, tags TEXT, completedDates TEXT, "
                        + "reminderEnabled INTEGER NOT NULL DEFAULT 0, "
                        + "weeklyTarget INTEGER NOT NULL DEFAULT 0, notes TEXT, "
                        + "ownerUsername TEXT)");
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

    private ContentValues v2Habit(long id, String title, String datesJson, String notesJson) {
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("title", title);
        cv.put("content", "内容");
        cv.put("reminderTime", "20:00");
        cv.put("createdAt", "2026-01-01 10:00");
        cv.put("category", "学习");
        cv.put("tags", "[]");
        cv.put("completedDates", datesJson);
        cv.put("reminderEnabled", 1);
        cv.put("weeklyTarget", 0);
        cv.put("notes", notesJson);
        cv.put("ownerUsername", "student");
        return cv;
    }

    @Test
    public void migration_2_3_splitsCheckInsIntoRecordTable() throws Exception {
        SupportSQLiteDatabase db = openV2Database();
        db.insert("habits", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                v2Habit(2001L, "晨跑",
                        "[\"2026-01-01\",\"2026-01-02\"]",
                        "{\"2026-01-01\":\"状态不错\"}"));
        db.insert("habits", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                v2Habit(2002L, "背单词", "[\"2026-01-03\"]", "{}"));

        StreakDatabase.MIGRATION_2_3.migrate(db);

        // 记录表：2001 两条、2002 一条，共三条
        Cursor c = db.query("SELECT COUNT(*) FROM check_in_records");
        assertTrue(c.moveToFirst());
        assertEquals("应迁出三条打卡记录", 3, c.getInt(0));
        c.close();

        // 备注落到对应日期
        c = db.query("SELECT note FROM check_in_records WHERE habitId = 2001 AND date = '2026-01-01'");
        assertTrue(c.moveToFirst());
        assertEquals("状态不错", c.getString(0));
        c.close();

        // 无备注日期 note 为 null
        c = db.query("SELECT note FROM check_in_records WHERE habitId = 2001 AND date = '2026-01-02'");
        assertTrue(c.moveToFirst());
        assertTrue("无备注应为 null", c.isNull(0));
        c.close();

        db.close();
    }

    @Test
    public void migration_2_3_dropsOldColumnsButKeepsHabitRows() throws Exception {
        SupportSQLiteDatabase db = openV2Database();
        db.insert("habits", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                v2Habit(2003L, "喝水", "[\"2026-01-01\"]", "{}"));

        StreakDatabase.MIGRATION_2_3.migrate(db);

        // habits 行仍在、业务字段不变
        Cursor c = db.query("SELECT title, ownerUsername FROM habits WHERE id = 2003");
        assertTrue(c.moveToFirst());
        assertEquals("喝水", c.getString(0));
        assertEquals("student", c.getString(1));
        c.close();

        // 两个旧列已物理移除
        c = db.query("PRAGMA table_info(habits)");
        boolean hasCompletedDates = false;
        boolean hasNotes = false;
        int nameIdx = c.getColumnIndexOrThrow("name");
        while (c.moveToNext()) {
            String col = c.getString(nameIdx);
            if ("completedDates".equals(col)) hasCompletedDates = true;
            if ("notes".equals(col)) hasNotes = true;
        }
        c.close();
        assertFalse("completedDates 列应已移除", hasCompletedDates);
        assertFalse("notes 列应已移除", hasNotes);

        db.close();
    }

    @Test
    public void migration_2_3_dedupesSameDate() throws Exception {
        SupportSQLiteDatabase db = openV2Database();
        // 存量数据里同一天重复出现（历史脏数据），迁移后按 (habitId,date) 唯一只留一条
        db.insert("habits", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                v2Habit(2004L, "拉伸",
                        "[\"2026-01-01\",\"2026-01-01\"]", "{}"));

        StreakDatabase.MIGRATION_2_3.migrate(db);

        Cursor c = db.query("SELECT COUNT(*) FROM check_in_records WHERE habitId = 2004");
        assertTrue(c.moveToFirst());
        assertEquals("重复日期应去重为一条", 1, c.getInt(0));
        c.close();
        db.close();
    }
}
