package com.streak.app.storage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.streak.app.db.StreakDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 密码边界口径单测（Robolectric + 真实 Room）。
 *
 * <p>覆盖修复点：注册/改密/登录三处对密码的处理口径必须一致——都不 trim。
 * 历史 bug 是登录页对密码做了 {@code trim()}，而注册/改密按原样（含首尾空格）哈希入库，
 * 导致「带首尾空格的密码注册后永远登录不上」。这里用真实 PBKDF2 往返验证口径已统一。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AppRepositoryPasswordTest {

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

    @Test
    public void passwordWithLeadingTrailingSpaces_registersAndLoginsWithSameString() {
        // 注册时带首尾空格；仓库按原样哈希（不 trim）
        assertNull(repository.registerAccount("spacey", "  pw123  "));
        // 用完全相同的串（含空格）应能登录——这正是登录不再 trim 的保证
        assertTrue("带空格的密码用同串应登录成功",
                repository.validateLogin("spacey", "  pw123  "));
    }

    @Test
    public void passwordWithSpaces_trimmedVariantIsRejected() {
        repository.registerAccount("spacey2", "  pw123  ");
        // 去掉空格的版本是不同的密码，必须被拒绝（证明空格是凭据的一部分，未被静默裁剪）
        assertFalse("裁剪掉空格的密码应登录失败", repository.validateLogin("spacey2", "pw123"));
    }

    @Test
    public void internalSpacesInPassword_preserved() {
        assertNull(repository.registerAccount("midspace", "a b c 1 2 3"));
        assertTrue(repository.validateLogin("midspace", "a b c 1 2 3"));
        assertFalse(repository.validateLogin("midspace", "abc123"));
    }

    @Test
    public void wrongPassword_rejected() {
        repository.registerAccount("normal", "correct-horse");
        assertFalse(repository.validateLogin("normal", "wrong-horse"));
        assertTrue(repository.validateLogin("normal", "correct-horse"));
    }
}
