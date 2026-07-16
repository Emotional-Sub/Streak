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

    /**
     * 损坏/非法 JSON 不应中断迁移：某行 completedDates/notes 是坏 JSON 时，
     * 该行按空列表容错跳过（parseList/parseMap 吞异常返回空），其它正常行照迁不误伤。
     */
    @Test
    public void migration_2_3_toleratesCorruptJson() throws Exception {
        SupportSQLiteDatabase db = openV2Database();
        // 2005：completedDates 是坏 JSON（缺右括号）；notes 也是坏的
        db.insert("habits", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                v2Habit(2005L, "损坏行", "[\"2026-01-01\"", "{bad json"));
        // 2006：正常行，用来确认坏行不拖累其它行
        db.insert("habits", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                v2Habit(2006L, "正常行", "[\"2026-02-01\"]", "{}"));

        StreakDatabase.MIGRATION_2_3.migrate(db);

        // 坏行迁出 0 条（容错跳过），不抛异常
        Cursor c = db.query("SELECT COUNT(*) FROM check_in_records WHERE habitId = 2005");
        assertTrue(c.moveToFirst());
        assertEquals("坏 JSON 行应容错为 0 条", 0, c.getInt(0));
        c.close();
        // 正常行不受影响
        c = db.query("SELECT COUNT(*) FROM check_in_records WHERE habitId = 2006");
        assertTrue(c.moveToFirst());
        assertEquals("正常行不应被坏行拖累", 1, c.getInt(0));
        c.close();
        db.close();
    }

    /**
     * 空/NULL 字段安全：completedDates 为 NULL、空数组、空串，notes 为 NULL，
     * 均按「无打卡」处理，迁出 0 条且不抛异常。
     */
    @Test
    public void migration_2_3_handlesEmptyAndNullFields() throws Exception {
        SupportSQLiteDatabase db = openV2Database();
        db.insert("habits", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                v2Habit(2007L, "NULL 打卡", null, null));
        db.insert("habits", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                v2Habit(2008L, "空数组", "[]", "{}"));
        db.insert("habits", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                v2Habit(2009L, "空串", "", ""));

        StreakDatabase.MIGRATION_2_3.migrate(db);

        Cursor c = db.query("SELECT COUNT(*) FROM check_in_records");
        assertTrue(c.moveToFirst());
        assertEquals("空/NULL 字段应迁出 0 条", 0, c.getInt(0));
        c.close();
        // 习惯行仍在，不丢
        c = db.query("SELECT COUNT(*) FROM habits");
        assertTrue(c.moveToFirst());
        assertEquals(3, c.getInt(0));
        c.close();
        db.close();
    }

    /**
     * 多账号：不同 ownerUsername 的习惯各自的打卡都应独立迁出，归属列保持不变，
     * 迁移不跨账号串数据。
     */
    @Test
    public void migration_2_3_preservesMultipleAccounts() throws Exception {
        SupportSQLiteDatabase db = openV2Database();
        ContentValues a = v2Habit(2010L, "A 的习惯", "[\"2026-01-01\",\"2026-01-02\"]", "{}");
        a.put("ownerUsername", "alice");
        ContentValues b = v2Habit(2011L, "B 的习惯", "[\"2026-01-01\"]", "{}");
        b.put("ownerUsername", "bob");
        db.insert("habits", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, a);
        db.insert("habits", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, b);

        StreakDatabase.MIGRATION_2_3.migrate(db);

        // 各账号习惯的记录数正确，归属列不变
        Cursor c = db.query("SELECT COUNT(*) FROM check_in_records WHERE habitId = 2010");
        assertTrue(c.moveToFirst());
        assertEquals(2, c.getInt(0));
        c.close();
        c = db.query("SELECT COUNT(*) FROM check_in_records WHERE habitId = 2011");
        assertTrue(c.moveToFirst());
        assertEquals(1, c.getInt(0));
        c.close();
        c = db.query("SELECT ownerUsername FROM habits WHERE id = 2010");
        assertTrue(c.moveToFirst());
        assertEquals("alice", c.getString(0));
        c.close();
        c = db.query("SELECT ownerUsername FROM habits WHERE id = 2011");
        assertTrue(c.moveToFirst());
        assertEquals("bob", c.getString(0));
        c.close();
        db.close();
    }

    /**
     * 升级后统计口径不变：迁移前 completedDates 里的「去重打卡日期数」应等于迁移后
     * check_in_records 里该习惯的记录数（全 App 统计都按天去重，这条保证升级前后计数一致）。
     */
    @Test
    public void migration_2_3_preservesUniqueCheckInCount() throws Exception {
        SupportSQLiteDatabase db = openV2Database();
        // 含一个重复日期：去重后应是 3 个不同日期
        db.insert("habits", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                v2Habit(2012L, "统计不变",
                        "[\"2026-01-01\",\"2026-01-02\",\"2026-01-03\",\"2026-01-02\"]",
                        "{\"2026-01-02\":\"备注\"}"));

        StreakDatabase.MIGRATION_2_3.migrate(db);

        Cursor c = db.query("SELECT COUNT(*) FROM check_in_records WHERE habitId = 2012");
        assertTrue(c.moveToFirst());
        assertEquals("迁移后记录数应等于去重日期数", 3, c.getInt(0));
        c.close();
        // 备注落到对应日期
        c = db.query("SELECT note FROM check_in_records WHERE habitId = 2012 AND date = '2026-01-02'");
        assertTrue(c.moveToFirst());
        assertEquals("备注", c.getString(0));
        c.close();
        db.close();
    }
}
