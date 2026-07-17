package com.streak.app.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.List;

/**
 * 归属修复策略测试（Robolectric + 真实 Room）。
 *
 * <p>验证登录时 {@code claimOrphanHabits} 只认领真正无主的习惯——owner 为空、或 owner 指向
 * 一个账号表里已不存在的用户名——而绝不触碰归属仍有效的其它账号习惯（不跨账号窃取）。
 * 这是「旧数据不永久固定 student、删号残留下次登录可恢复」的业务边界。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AppRepositoryClaimOrphanTest {

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

    /** 造一个指定归属的习惯并保存（绕过 saveHabit 的当前账号盖章，直写归属）。 */
    private HabitItem makeHabit(long id, String title, String owner) {
        HabitItem habit = new HabitItem();
        habit.setId(id);
        habit.setTitle(title);
        habit.setOwnerUsername(owner);
        habit.setCompletedDates(new ArrayList<>());
        return habit;
    }

    @Test
    public void claim_emptyOwnerHabit_isClaimedByLoginUser() {
        // 注册并登录 alice
        assertEquals(null, repository.registerAccount("alice", "pw123456"));
        repository.saveLoginState("alice", "pw123456", true, "alice");

        // 直写一个 owner 为空的孤儿习惯
        StreakDatabase.getInstance(context).habitDao().upsert(makeHabit(1001L, "无主习惯", ""));

        int claimed = repository.claimOrphanHabits("alice");
        assertEquals(1, claimed);

        // 认领后 alice 能读到它
        List<HabitItem> aliceHabits = repository.readHabits();
        boolean found = false;
        for (HabitItem h : aliceHabits) {
            if (h.getId() == 1001L) {
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    public void claim_habitOfDeletedAccount_isClaimed() {
        // 注册并登录 bob
        assertEquals(null, repository.registerAccount("bob", "pw123456"));
        repository.saveLoginState("bob", "pw123456", true, "bob");

        // 直写一个归属「ghost」的习惯——ghost 账号并不存在（模拟删号残留）
        StreakDatabase.getInstance(context).habitDao().upsert(makeHabit(2001L, "幽灵习惯", "ghost"));

        int claimed = repository.claimOrphanHabits("bob");
        assertEquals(1, claimed);

        List<HabitItem> bobHabits = repository.readHabits();
        boolean found = false;
        for (HabitItem h : bobHabits) {
            if (h.getId() == 2001L) {
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    public void claim_doesNotStealOtherValidAccountHabits() {
        // 两个真实账号：alice（登录者）与 carol
        assertEquals(null, repository.registerAccount("alice", "pw123456"));
        assertEquals(null, repository.registerAccount("carol", "pw123456"));
        repository.saveLoginState("alice", "pw123456", true, "alice");

        // carol 名下一个合法习惯 —— 归属有效，绝不能被 alice 认领
        StreakDatabase.getInstance(context).habitDao().upsert(makeHabit(3001L, "carol 的习惯", "carol"));

        int claimed = repository.claimOrphanHabits("alice");
        assertEquals(0, claimed);

        // alice 读不到 carol 的习惯
        List<HabitItem> aliceHabits = repository.readHabits();
        for (HabitItem h : aliceHabits) {
            assertFalse(h.getId() == 3001L);
        }
    }

    @Test
    public void claim_noOrphans_returnsZero() {
        assertEquals(null, repository.registerAccount("alice", "pw123456"));
        repository.saveLoginState("alice", "pw123456", true, "alice");
        // 只有 alice 自己的习惯（无孤儿）
        StreakDatabase.getInstance(context).habitDao().upsert(makeHabit(4001L, "alice 习惯", "alice"));
        assertEquals(0, repository.claimOrphanHabits("alice"));
    }
}
