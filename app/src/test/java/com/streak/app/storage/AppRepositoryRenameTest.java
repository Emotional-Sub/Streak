package com.streak.app.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.streak.app.db.StreakDatabase;
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
 * 账号改名的核心正确性单测（Robolectric + 真实 Room）。
 *
 * <p>覆盖 Phase 0 修复的最高危 bug：改名必须把该账号名下所有习惯的 ownerUsername
 * 同步迁移到新用户名，否则 readHabits() 走 getByOwner(新名) 会查不到旧习惯，
 * 用户会以为「改个名字自己所有习惯全没了」（实为永远查不出的孤儿数据）。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AppRepositoryRenameTest {

    private Context context;
    private AppRepository repository;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        StreakDatabase.resetForTest();
        context.deleteDatabase("streak.db");
        context.getSharedPreferences("java_streak_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        repository = new AppRepository(context);
    }

    @After
    public void tearDown() {
        StreakDatabase.resetForTest();
        context.deleteDatabase("streak.db");
    }

    // ---- 改名同步习惯归属（最高危路径） ----

    @Test
    public void rename_migratesHabitOwnership_habitsStillVisibleUnderNewName() {
        repository.registerAccount("oldname", "pw123456");
        loginAs("oldname");
        repository.saveHabit(newHabit(5001L, "晨跑", "oldname"));
        repository.saveHabit(newHabit(5002L, "背单词", "oldname"));
        assertEquals(2, repository.readHabits().size());

        // 改名：oldname -> newname
        String err = repository.updateAccount("oldname", "newname", "昵称", "签名", null, null);
        assertNull("改名应成功", err);

        // 登录态已切到 newname，习惯必须仍然可见（归属已迁移）
        assertEquals("newname", repository.getCurrentUser());
        List<HabitItem> habits = repository.readHabits();
        assertEquals("改名后习惯不应丢失", 2, habits.size());
        for (HabitItem h : habits) {
            assertEquals("习惯归属应已迁移到新用户名", "newname", h.getOwnerUsername());
        }
    }

    @Test
    public void rename_leavesNoOrphanUnderOldName() {
        repository.registerAccount("aaa", "pw123456");
        loginAs("aaa");
        repository.saveHabit(newHabit(6001L, "习惯", "aaa"));

        repository.updateAccount("aaa", "bbb", "n", "m", null, null);

        // 切回旧名读取应为空——旧名下不应残留任何孤儿习惯
        loginAs("aaa");
        assertTrue("旧用户名下不应有孤儿习惯", repository.readHabits().isEmpty());
    }

    @Test
    public void rename_toTakenUsername_isRejectedAndDataUntouched() {
        repository.registerAccount("userx", "pw123456");
        repository.registerAccount("usery", "pw123456");
        loginAs("userx");
        repository.saveHabit(newHabit(7001L, "x 的习惯", "userx"));

        // 改成已被占用的名字应失败
        String err = repository.updateAccount("userx", "usery", "n", "m", null, null);
        assertNotNull("改成已占用用户名应返回错误", err);

        // 失败后一切不动：登录态仍是 userx，习惯仍在
        assertEquals("userx", repository.getCurrentUser());
        assertEquals(1, repository.readHabits().size());
        assertEquals("x 的习惯", repository.readHabits().get(0).getTitle());
    }

    @Test
    public void rename_doesNotAffectOtherAccountsHabits() {
        repository.registerAccount("owner1", "pw123456");
        repository.registerAccount("owner2", "pw123456");

        loginAs("owner1");
        repository.saveHabit(newHabit(8001L, "1 的习惯", "owner1"));
        loginAs("owner2");
        repository.saveHabit(newHabit(8002L, "2 的习惯", "owner2"));

        // 改 owner1 的名字，owner2 的习惯不应受影响
        repository.updateAccount("owner1", "owner1_new", "n", "m", null, null);

        loginAs("owner2");
        assertEquals(1, repository.readHabits().size());
        assertEquals("2 的习惯", repository.readHabits().get(0).getTitle());
    }

    // ---- 辅助 ----

    private void loginAs(String username) {
        repository.saveLoginState(username, "ignored", true, username);
    }

    private HabitItem newHabit(long id, String title, String owner) {
        HabitItem item = new HabitItem(id, title, "内容", "20:00", "2026-01-01 10:00",
                null, "学习", new ArrayList<>(),
                new ArrayList<>(Arrays.asList("2026-01-01")), true);
        item.setOwnerUsername(owner);
        return item;
    }
}
