package com.streak.app.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.streak.app.model.CheckInRecord;
import com.streak.app.model.HabitItem;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;

/**
 * 乐观并发守卫与打卡输入校验回归测试。
 *
 * <p>覆盖三条此前无专项测试的路径：</p>
 * <ul>
 *   <li>打卡输入校验：非法 date / mood 越界 / 负 duration 一律拒绝（返回 false），不落库；</li>
 *   <li>打卡照片乐观并发：photoChanged=true 时若 DB 照片已被并发替换（不等于 expectedOriginal），
 *       原子拒绝，DB 保持并发方写入的最新照片；photoChanged=false 时保留 DB 当前照片；</li>
 *   <li>编辑页图片乐观并发：imageChanged=true 时若 DB 图片已被并发改动，拒绝保存不覆盖；
 *       imageChanged=false 时保留 DB 当前最新图片，不被旧快照写回。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class AppRepositoryConcurrencyGuardTest {

    private Context context;
    private AppRepository repository;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        com.streak.app.db.StreakDatabase.resetForTest();
        context.deleteDatabase("streak.db");
        context.getSharedPreferences("java_streak_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        repository = new AppRepository(context);
        repository.registerAccount("user1", "pw123456");
        loginAs("user1");
    }

    @After
    public void tearDown() {
        com.streak.app.db.StreakDatabase.resetForTest();
        context.deleteDatabase("streak.db");
    }

    // ---- 工作项8：打卡输入校验 ----

    @Test
    public void upsertCheckIn_invalidDate_isRejected() {
        repository.saveHabit(newHabit(8001L, "读书", "user1"));
        assertFalse(repository.upsertCheckIn(8001L, "2026-13-40", 0, 0, null, null));
        assertFalse(repository.upsertCheckIn(8001L, "not-a-date", 0, 0, null, null));
        assertFalse(repository.upsertCheckIn(8001L, "", 0, 0, null, null));
        assertNull(repository.getCheckIn(8001L, "2026-13-40"));
    }

    @Test
    public void upsertCheckIn_moodOutOfRange_isRejected() {
        repository.saveHabit(newHabit(8002L, "运动", "user1"));
        assertFalse(repository.upsertCheckIn(8002L, "2026-02-01", 6, 0, null, null));
        assertFalse(repository.upsertCheckIn(8002L, "2026-02-01", -1, 0, null, null));
        assertNull(repository.getCheckIn(8002L, "2026-02-01"));
    }

    @Test
    public void upsertCheckIn_negativeDuration_isRejected() {
        repository.saveHabit(newHabit(8003L, "冥想", "user1"));
        assertFalse(repository.upsertCheckIn(8003L, "2026-02-01", 0, -5, null, null));
        assertNull(repository.getCheckIn(8003L, "2026-02-01"));
    }

    @Test
    public void upsertCheckIn_validBoundaryValues_areAccepted() {
        repository.saveHabit(newHabit(8004L, "写作", "user1"));
        assertTrue(repository.upsertCheckIn(8004L, "2026-02-01", 5, 0, null, null));
        assertTrue(repository.upsertCheckIn(8004L, "2026-02-02", 0, 120, null, null));
        assertNotNull(repository.getCheckIn(8004L, "2026-02-01"));
    }

    // ---- 工作项4：打卡照片乐观并发 ----

    @Test
    public void upsertCheckInPhoto_staleExpectedPhoto_isRejectedAtomically() {
        repository.saveHabit(newHabit(8010L, "健身", "user1"));
        // 初次带照片 A 打卡（当天无记录，expectedOriginal=null，photoChanged=true）。
        assertTrue(repository.upsertCheckIn(8010L, "2026-02-01", 3, 10, null,
                "/img/A.jpg", null, true));
        // 并发方把照片替换成 B。
        assertTrue(repository.upsertCheckIn(8010L, "2026-02-01", 3, 10, null,
                "/img/B.jpg", "/img/A.jpg", true));
        // 旧快照仍以为原照片是 A，想换成 C —— DB 现为 B != A，必须原子拒绝。
        assertFalse(repository.upsertCheckIn(8010L, "2026-02-01", 3, 10, null,
                "/img/C.jpg", "/img/A.jpg", true));
        // DB 保持并发方写入的 B，未被旧快照覆盖。
        CheckInRecord record = repository.getCheckIn(8010L, "2026-02-01");
        assertNotNull(record);
        assertEquals("/img/B.jpg", record.getPhotoUri());
    }

    @Test
    public void upsertCheckInPhoto_unchanged_keepsDatabasePhoto() {
        repository.saveHabit(newHabit(8011L, "跑步", "user1"));
        assertTrue(repository.upsertCheckIn(8011L, "2026-02-01", 3, 10, null,
                "/img/A.jpg", null, true));
        // photoChanged=false：即便 photoUri 传别的值，也应保留 DB 当前照片 A，只更新其它字段。
        assertTrue(repository.upsertCheckIn(8011L, "2026-02-01", 5, 40, "更努力了",
                "/img/ignored.jpg", null, false));
        CheckInRecord record = repository.getCheckIn(8011L, "2026-02-01");
        assertNotNull(record);
        assertEquals("/img/A.jpg", record.getPhotoUri());
        assertEquals(5, record.getMood());
        assertEquals(40, record.getDurationMinutes());
    }

    // ---- 工作项3：编辑页图片乐观并发 ----

    @Test
    public void saveHabitImage_staleSnapshotOverwrite_isRejected() {
        HabitItem habit = newHabit(8020L, "早睡", "user1");
        habit.setImageUri("/img/O.jpg");
        assertTrue(repository.saveHabit(habit));
        // 另一页面把封面改成 X。
        HabitItem concurrent = repository.findHabitById(8020L);
        concurrent.setImageUri("/img/X.jpg");
        assertTrue(repository.saveHabit(concurrent, "/img/O.jpg", true));
        // 旧页面仍以为原图是 O，主动改成 Y —— DB 现为 X != O，拒绝，不覆盖 X。
        HabitItem stale = newHabit(8020L, "早睡", "user1");
        stale.setImageUri("/img/Y.jpg");
        assertFalse(repository.saveHabit(stale, "/img/O.jpg", true));
        assertEquals("/img/X.jpg", repository.findHabitById(8020L).getImageUri());
    }

    @Test
    public void saveHabitImage_unchanged_keepsLatestDatabaseImage() {
        HabitItem habit = newHabit(8021L, "喝水", "user1");
        habit.setImageUri("/img/O.jpg");
        assertTrue(repository.saveHabit(habit));
        // 另一页面把封面改成 X。
        HabitItem concurrent = repository.findHabitById(8021L);
        concurrent.setImageUri("/img/X.jpg");
        assertTrue(repository.saveHabit(concurrent, "/img/O.jpg", true));
        // 旧页面未主动改图（imageChanged=false），只改标题保存 —— 应保留 DB 最新图 X，不写回旧图 O。
        HabitItem stale = newHabit(8021L, "多喝水", "user1");
        stale.setImageUri("/img/O.jpg");
        assertTrue(repository.saveHabit(stale, "/img/O.jpg", false));
        HabitItem reloaded = repository.findHabitById(8021L);
        assertEquals("/img/X.jpg", reloaded.getImageUri());
        assertEquals("多喝水", reloaded.getTitle());
    }

    // ---- 辅助 ----

    private void loginAs(String username) {
        repository.saveLoginState(username, "ignored", true, username);
    }

    private HabitItem newHabit(long id, String title, String owner) {
        HabitItem item = new HabitItem(id, title, "内容", "20:00", "2026-01-01 10:00",
                null, "学习", new ArrayList<>(),
                new ArrayList<>(), true);
        item.setOwnerUsername(owner);
        return item;
    }
}
