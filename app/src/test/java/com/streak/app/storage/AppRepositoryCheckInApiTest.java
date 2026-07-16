package com.streak.app.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.streak.app.db.CheckInRecordDao;
import com.streak.app.db.StreakDatabase;
import com.streak.app.model.CheckInRecord;
import com.streak.app.model.HabitItem;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * 打卡真相源直写 API（{@link AppRepository#upsertCheckIn}/{@link AppRepository#removeCheckIn}
 * /{@link AppRepository#getCheckIn}）的单测。
 *
 * <p>新打卡 UI 不再走 completedDates/notes 派生字段，而是直接把心情/耗时/照片/备注落到
 * check_in_records 表。本测验证：</p>
 * <ul>
 *   <li>upsertCheckIn 写入全字段，(habitId,date) 冲突时原地覆盖当天那条；</li>
 *   <li>换照片时删除被替换掉的旧照片文件（不留磁盘孤儿图）；</li>
 *   <li>removeCheckIn 删记录并删其照片；</li>
 *   <li>直写后聚合回读，completedDates 能反映该打卡日期。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class AppRepositoryCheckInApiTest {

    private Context context;
    private AppRepository repository;
    private CheckInRecordDao recordDao;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        StreakDatabase.resetForTest();
        context.deleteDatabase("streak.db");
        context.getSharedPreferences("java_streak_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        repository = new AppRepository(context);
        recordDao = StreakDatabase.getInstance(context).checkInRecordDao();
    }

    @After
    public void tearDown() {
        StreakDatabase.resetForTest();
        context.deleteDatabase("streak.db");
    }

    @Test
    public void upsertCheckIn_writesAllFields() {
        repository.registerAccount("user1", "pw123456");
        loginAs("user1");
        repository.saveHabit(newHabit(7001L, "冥想", "user1"));

        repository.upsertCheckIn(7001L, "2026-02-01", 5, 30, "很专注", null);

        CheckInRecord record = repository.getCheckIn(7001L, "2026-02-01");
        assertNotNull(record);
        assertEquals(5, record.getMood());
        assertEquals(30, record.getDurationMinutes());
        assertEquals("很专注", record.getNote());
    }

    @Test
    public void upsertCheckIn_sameDateOverwritesInPlace() {
        repository.registerAccount("user1", "pw123456");
        loginAs("user1");
        repository.saveHabit(newHabit(7002L, "跑步", "user1"));

        repository.upsertCheckIn(7002L, "2026-02-01", 3, 10, "一般", null);
        repository.upsertCheckIn(7002L, "2026-02-01", 5, 40, "超棒", null);

        assertEquals("同一天应只有一条记录", 1, recordDao.getByHabit(7002L).size());
        CheckInRecord record = repository.getCheckIn(7002L, "2026-02-01");
        assertEquals(5, record.getMood());
        assertEquals(40, record.getDurationMinutes());
        assertEquals("超棒", record.getNote());
    }

    @Test
    public void upsertCheckIn_replacingPhotoDeletesOldFile() throws Exception {
        repository.registerAccount("user1", "pw123456");
        loginAs("user1");
        repository.saveHabit(newHabit(7003L, "阅读", "user1"));

        File oldPhoto = new File(context.getFilesDir(), "habit_images/old_photo.jpg");
        oldPhoto.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(oldPhoto)) {
            fos.write(new byte[]{1, 2, 3});
        }
        String oldUri = android.net.Uri.fromFile(oldPhoto).toString();

        repository.upsertCheckIn(7003L, "2026-02-01", 4, 15, null, oldUri);
        assertTrue("旧照片应存在", oldPhoto.exists());

        // 换成新照片：旧照片文件应被删除
        File newPhoto = new File(context.getFilesDir(), "habit_images/new_photo.jpg");
        try (FileOutputStream fos = new FileOutputStream(newPhoto)) {
            fos.write(new byte[]{4, 5, 6});
        }
        String newUri = android.net.Uri.fromFile(newPhoto).toString();
        repository.upsertCheckIn(7003L, "2026-02-01", 4, 15, null, newUri);

        assertFalse("换照片后旧文件应被删除", oldPhoto.exists());
        assertTrue("新照片应保留", newPhoto.exists());
        assertEquals(newUri, repository.getCheckIn(7003L, "2026-02-01").getPhotoUri());
    }

    @Test
    public void removeCheckIn_deletesRecordAndPhoto() throws Exception {
        repository.registerAccount("user1", "pw123456");
        loginAs("user1");
        repository.saveHabit(newHabit(7004L, "喝水", "user1"));

        File photo = new File(context.getFilesDir(), "habit_images/checkin.jpg");
        photo.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(photo)) {
            fos.write(new byte[]{7, 8, 9});
        }
        String uri = android.net.Uri.fromFile(photo).toString();
        repository.upsertCheckIn(7004L, "2026-02-01", 4, 15, null, uri);

        repository.removeCheckIn(7004L, "2026-02-01");

        assertNull("记录应被删除", repository.getCheckIn(7004L, "2026-02-01"));
        assertFalse("附带照片应被删除", photo.exists());
    }

    @Test
    public void upsertCheckIn_reflectedInAggregatedCompletedDates() {
        repository.registerAccount("user1", "pw123456");
        loginAs("user1");
        repository.saveHabit(newHabit(7005L, "写作", "user1"));

        repository.upsertCheckIn(7005L, "2026-02-01", 4, 20, null, null);
        repository.upsertCheckIn(7005L, "2026-02-02", 4, 20, null, null);

        HabitItem reloaded = repository.findHabitById(7005L);
        assertEquals(2, reloaded.getCompletedDates().size());
        assertTrue(reloaded.getCompletedDates().contains("2026-02-01"));
        assertTrue(reloaded.getCompletedDates().contains("2026-02-02"));
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
