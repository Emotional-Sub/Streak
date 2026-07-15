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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 打卡记录规范化（check_in_records 表作真相源）的核心行为单测。
 *
 * <p>验证 v2->v3 后的关键契约：打卡数据的真相源是 check_in_records 表，
 * 而 {@link HabitItem} 的 completedDates/notes 已降为「读时聚合回填」的内存派生视图。
 * 既有约 90 处消费端仍读这两个字段，故必须保证：</p>
 * <ul>
 *   <li>saveHabit 时把派生字段同步进记录表；readHabits/findHabitById 读时聚合回填；</li>
 *   <li>撤销某天打卡（从 completedDates 移除）会删对应记录；</li>
 *   <li>对保留的日期，再次保存不会抹掉该记录已有的 mood/duration/photo（增量 diff，非重建）；</li>
 *   <li>删习惯会一并删其打卡记录（无外键级联，Repository 手动清理），且不跨账号误删。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class AppRepositoryCheckInRecordTest {

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

    // ---- 保存 -> 记录表；读取 -> 聚合回填 ----

    @Test
    public void saveHabit_writesCheckInsToRecordTable_andReadAggregatesBack() {
        repository.registerAccount("user1", "pw123456");
        loginAs("user1");

        HabitItem habit = newHabit(9001L, "晨跑", "user1");
        habit.setCompletedDates(new ArrayList<>(Arrays.asList("2026-01-01", "2026-01-02")));
        habit.setNote("2026-01-01", "状态不错");
        repository.saveHabit(habit);

        // 记录表里应有两条，备注落到对应日期
        List<CheckInRecord> records = recordDao.getByHabit(9001L);
        assertEquals("应写入两条打卡记录", 2, records.size());

        // 读回：completedDates/notes 应由记录聚合回填
        HabitItem reloaded = repository.findHabitById(9001L);
        assertNotNull(reloaded);
        assertEquals("聚合回填应还原两个打卡日期", 2, reloaded.getCompletedDates().size());
        assertTrue(reloaded.getCompletedDates().contains("2026-01-01"));
        assertTrue(reloaded.getCompletedDates().contains("2026-01-02"));
        assertEquals("备注应聚合回填", "状态不错", reloaded.getNote("2026-01-01"));
        assertEquals("无备注的日期回填空串", "", reloaded.getNote("2026-01-02"));
    }

    @Test
    public void unCheckingDate_removesItsRecord() {
        repository.registerAccount("user1", "pw123456");
        loginAs("user1");

        HabitItem habit = newHabit(9002L, "背单词", "user1");
        habit.setCompletedDates(new ArrayList<>(Arrays.asList("2026-01-01", "2026-01-02")));
        repository.saveHabit(habit);
        assertEquals(2, recordDao.getByHabit(9002L).size());

        // 撤销 2026-01-02 的打卡：从派生字段移除后保存
        HabitItem reloaded = repository.findHabitById(9002L);
        List<String> dates = new ArrayList<>(reloaded.getCompletedDates());
        dates.remove("2026-01-02");
        reloaded.setCompletedDates(dates);
        repository.saveHabit(reloaded);

        List<CheckInRecord> records = recordDao.getByHabit(9002L);
        assertEquals("撤销后应只剩一条记录", 1, records.size());
        assertEquals("2026-01-01", records.get(0).getDate());
    }

    @Test
    public void resavingKeptDate_preservesMoodDurationPhoto() {
        repository.registerAccount("user1", "pw123456");
        loginAs("user1");

        HabitItem habit = newHabit(9003L, "冥想", "user1");
        habit.setCompletedDates(new ArrayList<>(Arrays.asList("2026-01-01")));
        repository.saveHabit(habit);

        // 直接在记录表上补齐心情/耗时/照片（模拟新 UI 写入的富信息）
        CheckInRecord record = recordDao.getByHabitAndDate(9003L, "2026-01-01");
        assertNotNull(record);
        record.setMood(5);
        record.setDurationMinutes(20);
        record.setPhotoUri("file:///photo/checkin.jpg");
        recordDao.upsert(record);

        // 既有打卡 UI 再保存（只带日期/备注，不含富字段）——不能把富字段抹掉
        HabitItem reloaded = repository.findHabitById(9003L);
        reloaded.setNote("2026-01-01", "很平静");
        repository.saveHabit(reloaded);

        CheckInRecord after = recordDao.getByHabitAndDate(9003L, "2026-01-01");
        assertNotNull(after);
        assertEquals("心情应保留", 5, after.getMood());
        assertEquals("耗时应保留", 20, after.getDurationMinutes());
        assertEquals("照片应保留", "file:///photo/checkin.jpg", after.getPhotoUri());
        assertEquals("备注应更新", "很平静", after.getNote());
    }

    // ---- 删习惯 -> 记录随之清理，且不跨账号 ----

    @Test
    public void deleteHabit_alsoDeletesItsRecords() {
        repository.registerAccount("user1", "pw123456");
        loginAs("user1");

        HabitItem habit = newHabit(9004L, "喝水", "user1");
        habit.setCompletedDates(new ArrayList<>(Arrays.asList("2026-01-01", "2026-01-02")));
        repository.saveHabit(habit);
        assertEquals(2, recordDao.getByHabit(9004L).size());

        repository.deleteHabitById(9004L);

        assertTrue("删习惯后其打卡记录应一并清空", recordDao.getByHabit(9004L).isEmpty());
    }

    @Test
    public void deleteHabit_doesNotTouchOtherAccountRecords() {
        repository.registerAccount("owner1", "pw123456");
        repository.registerAccount("owner2", "pw123456");

        loginAs("owner1");
        HabitItem h1 = newHabit(9005L, "1 的习惯", "owner1");
        h1.setCompletedDates(new ArrayList<>(Arrays.asList("2026-01-01")));
        repository.saveHabit(h1);

        loginAs("owner2");
        HabitItem h2 = newHabit(9006L, "2 的习惯", "owner2");
        h2.setCompletedDates(new ArrayList<>(Arrays.asList("2026-01-01")));
        repository.saveHabit(h2);

        // owner2 登录态下试图删 owner1 的习惯 id：既删不到习惯，也不能误删其记录
        repository.deleteHabitById(9005L);

        assertEquals("不应跨账号误删他人打卡记录", 1, recordDao.getByHabit(9005L).size());
        assertEquals("本账号记录不受影响", 1, recordDao.getByHabit(9006L).size());
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
