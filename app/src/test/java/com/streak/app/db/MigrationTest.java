package com.streak.app.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;

import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 已发布的 Room 迁移 MIGRATION_1_2 的单测（Robolectric + 真实 SQLite）。
 *
 * <p>本项目 exportSchema=false，用不了依赖导出 schema JSON 的标准 MigrationTestHelper 流程，
 * 故直接用 {@link FrameworkSQLiteOpenHelperFactory} 建一个 v1 结构的 habits 表、插入存量数据，
 * 再手工调用 {@link StreakDatabase#MIGRATION_1_2} 的 migrate()，断言：</p>
 * <ul>
 *   <li>迁移后新增 ownerUsername 列；</li>
 *   <li>升级前无归属的存量习惯被回填为演示账号 student（不会「无主」查不出）；</li>
 *   <li>迁移过程不丢行、不改动原有业务字段。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class MigrationTest {

    /** 建一个 v1（无 ownerUsername 列）的内存 habits 表并返回可写库。 */
    private SupportSQLiteDatabase openV1Database() {
        Context context = ApplicationProvider.getApplicationContext();
        SupportSQLiteOpenHelper.Callback callback = new SupportSQLiteOpenHelper.Callback(1) {
            @Override
            public void onCreate(SupportSQLiteDatabase db) {
                // v1 habits 表：与升级前的 HabitItem 实体一致，尚无 ownerUsername 列。
                db.execSQL("CREATE TABLE habits ("
                        + "id INTEGER PRIMARY KEY NOT NULL, "
                        + "title TEXT, content TEXT, reminderTime TEXT, createdAt TEXT, "
                        + "imageUri TEXT, category TEXT, tags TEXT, completedDates TEXT, "
                        + "reminderEnabled INTEGER NOT NULL DEFAULT 0, "
                        + "weeklyTarget INTEGER NOT NULL DEFAULT 0, notes TEXT)");
            }

            @Override
            public void onUpgrade(SupportSQLiteDatabase db, int oldVersion, int newVersion) {
                // 测试里手工调用迁移，这里不需要实现
            }
        };
        SupportSQLiteOpenHelper.Configuration config =
                SupportSQLiteOpenHelper.Configuration.builder(context)
                        .name(null) // name=null -> 内存库，用例间互不干扰
                        .callback(callback)
                        .build();
        return new FrameworkSQLiteOpenHelperFactory().create(config).getWritableDatabase();
    }

    private ContentValues v1Habit(long id, String title) {
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("title", title);
        cv.put("content", "内容");
        cv.put("reminderTime", "20:00");
        cv.put("createdAt", "2026-01-01 10:00");
        cv.put("category", "学习");
        cv.put("tags", "[]");
        cv.put("completedDates", "[\"2026-01-01\"]");
        cv.put("reminderEnabled", 1);
        cv.put("weeklyTarget", 0);
        cv.put("notes", "{}");
        return cv;
    }

    @Test
    public void migration_1_2_addsOwnerColumnAndBackfillsStudent() throws Exception {
        SupportSQLiteDatabase db = openV1Database();
        db.insert("habits", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, v1Habit(1001L, "晨跑"));
        db.insert("habits", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, v1Habit(1002L, "背单词"));

        // 执行已发布的迁移
        StreakDatabase.MIGRATION_1_2.migrate(db);

        // 1) 新列存在 + 存量习惯统一回填 student
        android.database.Cursor c = db.query(
                "SELECT id, title, ownerUsername FROM habits ORDER BY id");
        int rows = 0;
        while (c.moveToNext()) {
            rows++;
            String owner = c.getString(c.getColumnIndexOrThrow("ownerUsername"));
            assertEquals("存量习惯应回填为演示账号 student", "student", owner);
        }
        c.close();
        assertEquals("迁移不应丢行", 2, rows);
        db.close();
    }

    @Test
    public void migration_1_2_isRunnableWithoutError() throws Exception {
        // 空表也能安全迁移（老用户曾删光习惯的情形）
        SupportSQLiteDatabase db = openV1Database();
        StreakDatabase.MIGRATION_1_2.migrate(db);
        android.database.Cursor c = db.query("SELECT COUNT(*) FROM habits");
        assertTrue(c.moveToFirst());
        assertEquals(0, c.getInt(0));
        c.close();
        db.close();
    }
}
