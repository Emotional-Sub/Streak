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

    // ---- 打卡写入走直写 API；读取 -> 聚合回填 ----

    @Test
    public void directWriteCheckIns_toRecordTable_andReadAggregatesBack() {
        repository.registerAccount("user1", "pw123456");
        loginAs("user1");

        // 习惯元数据经 saveHabit 落地；打卡走直写 API（真相源），不再依赖 saveHabit 回写。
        repository.saveHabit(newHabit(9001L, "晨跑", "user1"));
        repository.upsertCheckIn(9001L, "2026-01-01", 0, 0, "状态不错", null);
        repository.upsertCheckIn(9001L, "2026-01-02", 0, 0, null, null);

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
    public void removeCheckIn_removesItsRecord() {
        repository.registerAccount("user1", "pw123456");
        loginAs("user1");

        repository.saveHabit(newHabit(9002L, "背单词", "user1"));
        repository.upsertCheckIn(9002L, "2026-01-01", 0, 0, null, null);
        repository.upsertCheckIn(9002L, "2026-01-02", 0, 0, null, null);
        assertEquals(2, recordDao.getByHabit(9002L).size());

        // 撤销 2026-01-02 的打卡：走直写 API removeCheckIn（不再经 saveHabit 回写）
        repository.removeCheckIn(9002L, "2026-01-02");

        List<CheckInRecord> records = recordDao.getByHabit(9002L);
        assertEquals("撤销后应只剩一条记录", 1, records.size());
        assertEquals("2026-01-01", records.get(0).getDate());
    }

    /**
     * 核心回归：保存习惯「元数据」（标题/分类/提醒等）绝不能删除或覆盖已有打卡的
     * mood/duration/note/photo。saveHabit 已只写习惯行、不再回写打卡记录——本用例
     * 正是防止「编辑页持过期快照 -> saveHabit 重新同步 -> 误删/覆盖打卡富字段」的回归守卫。
     */
    @Test
    public void savingHabitMetadata_preservesExistingCheckInRichFields() {
        repository.registerAccount("user1", "pw123456");
        loginAs("user1");

        repository.saveHabit(newHabit(9003L, "冥想", "user1"));
        // 用直写 API 落一条带完整富字段的打卡
        repository.upsertCheckIn(9003L, "2026-01-01", 5, 20, "很平静", "file:///photo/checkin.jpg");

        // 模拟编辑页：读出习惯（其 completedDates 已聚合回填），仅改元数据后保存。
        // 关键：即便此时 reloaded 持有的是「读时快照」，saveHabit 也不得据此回写打卡表。
        HabitItem reloaded = repository.findHabitById(9003L);
        assertNotNull(reloaded);
        reloaded.setTitle("正念冥想");
        reloaded.setCategory("生活");
        reloaded.setReminderTime("21:30");
        repository.saveHabit(reloaded);

        // 打卡富字段必须原样保留
        CheckInRecord after = recordDao.getByHabitAndDate(9003L, "2026-01-01");
        assertNotNull("打卡记录不应被保存元数据删除", after);
        assertEquals("心情应保留", 5, after.getMood());
        assertEquals("耗时应保留", 20, after.getDurationMinutes());
        assertEquals("照片应保留", "file:///photo/checkin.jpg", after.getPhotoUri());
        assertEquals("备注应保留", "很平静", after.getNote());

        // 元数据确实更新了
        HabitItem afterHabit = repository.findHabitById(9003L);
        assertEquals("正念冥想", afterHabit.getTitle());
        assertEquals("生活", afterHabit.getCategory());
        assertEquals("21:30", afterHabit.getReminderTime());
    }

    /**
     * 回归：编辑页可能持有「打卡日期已过期」的习惯快照——例如加载后、保存前，
     * 后台并发新增了一条打卡。saveHabit 绝不能按这份过期的 completedDates 反向同步，
     * 否则会误删并发产生的那条打卡记录。
     */
    @Test
    public void savingStaleHabitSnapshot_doesNotDeleteConcurrentCheckIn() {
        repository.registerAccount("user1", "pw123456");
        loginAs("user1");

        repository.saveHabit(newHabit(9007L, "阅读", "user1"));
        repository.upsertCheckIn(9007L, "2026-01-01", 0, 0, null, null);

        // 编辑页加载出快照：此刻只有 01-01 一条打卡
        HabitItem staleSnapshot = repository.findHabitById(9007L);
        assertEquals(1, staleSnapshot.getCompletedDates().size());

        // 并发：后台又打卡了 01-02（直写 API）
        repository.upsertCheckIn(9007L, "2026-01-02", 0, 0, null, null);

        // 编辑页用过期快照保存（其 completedDates 仍只含 01-01）
        repository.saveHabit(staleSnapshot);

        // 并发新增的 01-02 不能被误删
        List<CheckInRecord> records = recordDao.getByHabit(9007L);
        assertEquals("过期快照保存不得删除并发打卡", 2, records.size());
        assertNotNull(recordDao.getByHabitAndDate(9007L, "2026-01-02"));
    }

    // ---- 删习惯 -> 记录随之清理，且不跨账号 ----

    @Test
    public void deleteHabit_alsoDeletesItsRecords() {
        repository.registerAccount("user1", "pw123456");
        loginAs("user1");

        repository.saveHabit(newHabit(9004L, "喝水", "user1"));
        // 打卡走直写 API（saveHabit 已不再回写打卡记录）
        repository.upsertCheckIn(9004L, "2026-01-01", 0, 0, null, null);
        repository.upsertCheckIn(9004L, "2026-01-02", 0, 0, null, null);
        assertEquals(2, recordDao.getByHabit(9004L).size());

        repository.deleteHabitById(9004L);

        assertTrue("删习惯后其打卡记录应一并清空", recordDao.getByHabit(9004L).isEmpty());
    }

    @Test
    public void deleteHabit_doesNotTouchOtherAccountRecords() {
        repository.registerAccount("owner1", "pw123456");
        repository.registerAccount("owner2", "pw123456");

        loginAs("owner1");
        repository.saveHabit(newHabit(9005L, "1 的习惯", "owner1"));
        repository.upsertCheckIn(9005L, "2026-01-01", 0, 0, null, null);

        loginAs("owner2");
        repository.saveHabit(newHabit(9006L, "2 的习惯", "owner2"));
        repository.upsertCheckIn(9006L, "2026-01-01", 0, 0, null, null);

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
