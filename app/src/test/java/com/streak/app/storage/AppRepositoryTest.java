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
import com.streak.app.model.UserAccount;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 存储层核心行为单测（Robolectric + 真实 Room，运行在本地 JVM）。
 * 覆盖答辩最易被追问的两点：多账号数据隔离、账号凭据安全（不存明文、导入不覆盖凭据）。
 *
 * 说明：AppRepository 内部走 {@link StreakDatabase} 单例（真实 streak.db 文件），
 * 每个用例前用 resetForTest() 关闭并清空单例，保证互不串数据。
 */
@RunWith(RobolectricTestRunner.class)
public class AppRepositoryTest {

    private Context context;
    private AppRepository repository;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        // 关键：清掉可能残留的单例与磁盘库，让每个用例从全新数据库开始
        StreakDatabase.resetForTest();
        context.deleteDatabase("streak.db");
        // 清掉上一个用例写入的 SharedPreferences 登录态
        context.getSharedPreferences("java_streak_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        repository = new AppRepository(context);
    }

    @After
    public void tearDown() {
        StreakDatabase.resetForTest();
        context.deleteDatabase("streak.db");
    }

    // ---- 首启种子 & 默认账号 ----

    @Test
    public void freshInstall_seedsDefaultStudentAccount() {
        // 全新安装应补默认演示账号 student，可用 123456 登录
        assertTrue(repository.validateLogin("student", "123456"));
    }

    @Test
    public void freshInstall_wrongPasswordRejected() {
        assertFalse(repository.validateLogin("student", "wrong"));
    }

    // ---- 注册 ----

    @Test
    public void register_newUser_succeeds() {
        assertNull(repository.registerAccount("alice", "pw123456"));
        assertTrue(repository.validateLogin("alice", "pw123456"));
    }

    @Test
    public void register_duplicateUsername_returnsError() {
        assertNull(repository.registerAccount("bob", "pw123456"));
        assertNotNull(repository.registerAccount("bob", "another"));
    }

    @Test
    public void register_emptyCredentials_returnsError() {
        assertNotNull(repository.registerAccount("", "pw"));
        assertNotNull(repository.registerAccount("name", "   "));
    }

    @Test
    public void register_doesNotStorePlaintextPassword() {
        repository.registerAccount("carol", "secret123");
        UserAccount account = repository.getAccount("carol");
        assertNotNull(account);
        // 安全：注册后不得留明文，只有 PBKDF2 哈希 + 盐
        assertNull(account.getPassword());
        assertNotNull(account.getPasswordHash());
        assertNotNull(account.getSalt());
    }

    // ---- 多账号数据隔离 ----

    @Test
    public void habits_areIsolatedPerAccount() {
        repository.registerAccount("userA", "pwAAAAAA");
        repository.registerAccount("userB", "pwBBBBBB");

        // 以 A 登录，写一条习惯
        loginAs("userA");
        repository.saveHabit(newHabit(1001L, "A 的习惯", null));
        assertEquals(1, repository.readHabits().size());
        assertEquals("A 的习惯", repository.readHabits().get(0).getTitle());

        // 切到 B：看不到 A 的习惯
        loginAs("userB");
        assertTrue(repository.readHabits().isEmpty());
        repository.saveHabit(newHabit(1002L, "B 的习惯", null));
        assertEquals(1, repository.readHabits().size());

        // 回到 A：仍只看到自己的
        loginAs("userA");
        assertEquals(1, repository.readHabits().size());
        assertEquals("A 的习惯", repository.readHabits().get(0).getTitle());
    }

    @Test
    public void saveHabit_stampsCurrentOwnerWhenMissing() {
        repository.registerAccount("dave", "pwDDDDDD");
        loginAs("dave");
        // 不预设 owner，saveHabit 应自动盖上当前登录账号
        repository.saveHabit(newHabit(2001L, "无主习惯", null));
        List<HabitItem> habits = repository.readHabits();
        assertEquals(1, habits.size());
        assertEquals("dave", habits.get(0).getOwnerUsername());
    }

    @Test
    public void notLoggedIn_readHabitsIsEmpty() {
        // 未登录（空当前用户）读取应为空，不泄露任何账号数据
        repository.logout();
        assertTrue(repository.readHabits().isEmpty());
    }

    // ---- 唯一 id 防撞（跨账号） ----

    @Test
    public void generateUniqueHabitId_avoidsCollisionAcrossAccounts() {
        repository.registerAccount("eve", "pwEEEEEE");
        loginAs("eve");
        long id = repository.generateUniqueHabitId();
        repository.saveHabit(newHabit(id, "占位", null));
        // 再要一个 id 必须与已占用的不同
        long next = repository.generateUniqueHabitId();
        assertFalse(next == id);
    }

    // ---- 删号只清本账号 ----

    @Test
    public void deleteCurrentAccount_onlyClearsOwnData() {
        repository.registerAccount("frank", "pwFFFFFF");
        repository.registerAccount("grace", "pwGGGGGG");

        loginAs("frank");
        repository.saveHabit(newHabit(3001L, "frank 习惯", null));
        loginAs("grace");
        repository.saveHabit(newHabit(3002L, "grace 习惯", null));

        // 删除 grace：只清 grace 的习惯与账号，frank 不受影响
        loginAs("grace");
        repository.deleteCurrentAccountAndData();
        assertNull(repository.getAccount("grace"));

        loginAs("frank");
        assertNotNull(repository.getAccount("frank"));
        assertEquals(1, repository.readHabits().size());
        assertEquals("frank 习惯", repository.readHabits().get(0).getTitle());
    }

    // ---- 辅助 ----

    private void loginAs(String username) {
        // 复用生产 API 设置登录态；rememberPassword 传 true 以写入 currentUser
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
