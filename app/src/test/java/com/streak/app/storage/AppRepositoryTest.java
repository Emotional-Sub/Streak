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

import java.io.File;
import java.io.FileOutputStream;
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
        HabitItem input = newHabit(2001L, "无主习惯", null);
        assertTrue(repository.saveHabit(input));
        assertNull("saveHabit must not mutate the caller's object", input.getOwnerUsername());
        List<HabitItem> habits = repository.readHabits();
        assertEquals(1, habits.size());
        assertEquals("dave", habits.get(0).getOwnerUsername());
    }

    @Test
    public void saveHabit_cannotForgeOwnerToOverwriteAnotherAccount() {
        repository.registerAccount("alice", "pwAAAAAA");
        repository.registerAccount("bob", "pwBBBBBB");

        loginAs("bob");
        repository.saveHabit(newHabit(2101L, "Bob 的原习惯", "bob"));

        // 即使调用方伪造 owner 为当前账号，已有的他人主键也不能被 upsert 覆盖。
        loginAs("alice");
        HabitItem forged = newHabit(2101L, "Alice 的伪造习惯", "alice");
        repository.saveHabit(forged);
        assertTrue(repository.readHabits().isEmpty());

        loginAs("bob");
        assertEquals(1, repository.readHabits().size());
        assertEquals("Bob 的原习惯", repository.readHabits().get(0).getTitle());
    }

    @Test
    public void writeHabits_rejectsMixedOwnersWithoutClearingExistingData() {
        repository.registerAccount("alice", "pwAAAAAA");
        repository.registerAccount("bob", "pwBBBBBB");

        loginAs("alice");
        repository.saveHabit(newHabit(2201L, "Alice 的原习惯", "alice"));

        // 批次中显式带有他人归属时，必须在任何删除前整体拒绝。
        HabitItem own = newHabit(2202L, "新的 Alice 习惯", null);
        HabitItem foreign = newHabit(2203L, "不应写入的 Bob 习惯", "bob");
        repository.writeHabits(Arrays.asList(own, foreign));

        assertEquals(1, repository.readHabits().size());
        assertEquals("Alice 的原习惯", repository.readHabits().get(0).getTitle());
        assertNull(own.getOwnerUsername());
        assertEquals("bob", foreign.getOwnerUsername());
    }

    @Test
    public void writeHabits_rejectsDuplicateIdsWithoutClearingExistingData() {
        repository.registerAccount("alice", "pwAAAAAA");
        loginAs("alice");
        repository.saveHabit(newHabit(2301L, "Alice 的原习惯", "alice"));

        HabitItem first = newHabit(2302L, "重复 ID 第一项", null);
        HabitItem second = newHabit(2302L, "重复 ID 第二项", null);
        repository.writeHabits(Arrays.asList(first, second));

        assertEquals(1, repository.readHabits().size());
        assertEquals("Alice 的原习惯", repository.readHabits().get(0).getTitle());
        assertNull(first.getOwnerUsername());
        assertNull(second.getOwnerUsername());
    }

    @Test
    public void writeHabits_rejectsForeignIdAtomicallyAndPreservesInput() {
        repository.registerAccount("alice", "pwAAAAAA");
        repository.registerAccount("bob", "pwBBBBBB");

        loginAs("bob");
        repository.saveHabit(newHabit(2402L, "Bob 的原习惯", "bob"));
        loginAs("alice");
        repository.saveHabit(newHabit(2401L, "Alice 的原习惯", "alice"));

        // owner 都伪造成 Alice，只有事务内的全表主键归属检查才能挡住第二项。
        HabitItem replacement = newHabit(2403L, "不应替换的 Alice 习惯", null);
        HabitItem forgedForeignId = newHabit(2402L, "不应覆盖 Bob 的习惯", "alice");
        repository.writeHabits(Arrays.asList(replacement, forgedForeignId));

        assertEquals(1, repository.readHabits().size());
        assertEquals("Alice 的原习惯", repository.readHabits().get(0).getTitle());
        assertNull(replacement.getOwnerUsername());
        assertEquals("alice", forgedForeignId.getOwnerUsername());

        loginAs("bob");
        assertEquals(1, repository.readHabits().size());
        assertEquals("Bob 的原习惯", repository.readHabits().get(0).getTitle());
    }

    @Test
    public void writeHabits_successDoesNotMutateInputOwnerOrCollections() {
        repository.registerAccount("alice", "pwAAAAAA");
        loginAs("alice");

        List<String> tags = new ArrayList<>(Arrays.asList("阅读", "早起"));
        List<String> completedDates = new ArrayList<>(Arrays.asList("2026-01-01"));
        HabitItem input = newHabit(2501L, "输入对象", null);
        input.setTags(tags);
        input.setCompletedDates(completedDates);
        repository.writeHabits(Arrays.asList(input));

        // 归属盖章和打卡同步应只作用于仓库副本，不能反向修改调用方对象。
        assertNull(input.getOwnerUsername());
        assertEquals(tags, input.getTags());
        assertEquals(completedDates, input.getCompletedDates());
        assertEquals("alice", repository.readHabits().get(0).getOwnerUsername());
    }

    @Test
    public void deleteHabit_deletesSharedImageOnlyAfterLastReference() throws Exception {
        repository.registerAccount("alice", "pwAAAAAA");
        loginAs("alice");

        File imageDir = new File(context.getFilesDir(), "habit_images");
        assertTrue(imageDir.mkdirs() || imageDir.isDirectory());
        File sharedImage = new File(imageDir, "shared-delete-test.jpg");
        try (FileOutputStream output = new FileOutputStream(sharedImage)) {
            output.write(1);
        }

        HabitItem first = newHabit(2601L, "第一条共享图片习惯", null);
        first.setImageUri(sharedImage.getAbsolutePath());
        HabitItem second = newHabit(2602L, "第二条共享图片习惯", null);
        second.setImageUri(sharedImage.getAbsolutePath());
        assertTrue(repository.saveHabit(first));
        assertTrue(repository.saveHabit(second));

        assertTrue(repository.deleteHabitById(first.getId()));
        assertTrue("the remaining reference must keep the shared file", sharedImage.exists());
        assertTrue(repository.deleteHabitById(second.getId()));
        assertFalse("the last removed reference must release the shared file", sharedImage.exists());
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
