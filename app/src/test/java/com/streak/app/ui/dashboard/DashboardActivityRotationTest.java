package com.streak.app.ui.dashboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Looper;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.streak.app.R;
import com.streak.app.db.StreakDatabase;
import com.streak.app.storage.AppRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;

/**
 * Dashboard 配置重建（旋转）生命周期回归测试——补齐此前只有代码审查保证的 UI 并发路径。
 *
 * <p>覆盖两条评估点名、此前无自动化测试的路径：</p>
 * <ul>
 *   <li><b>旋转复原选中页</b>：切到非默认页后旋转重建，应回到旋转前那一页（而非无条件回习惯页）。
 *       依据 {@code onSaveInstanceState/onCreate} 里 {@code selected_nav_id} 的存取。</li>
 *   <li><b>退出进行中态跨旋转存活</b>：退出门闩置位后旋转重建，新 Activity 应据共享 ViewModel 的
 *       {@code isLogoutInProgress()} 重新禁用底部导航——退出不会因旋转「复活」可点。</li>
 * </ul>
 *
 * <p>用 Robolectric {@link ActivityController#recreate()} 真跑一遍配置重建，而非直接断言字段。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class DashboardActivityRotationTest {

    private Context context;
    private ActivityController<DashboardActivity> controller;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        StreakDatabase.resetForTest();
        context.deleteDatabase("streak.db");
        context.getSharedPreferences("java_streak_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        // 建一个已登录账号，onCreate 才不会因 getCurrentUser().isEmpty() 跳回登录页。
        AppRepository repository = new AppRepository(context);
        repository.registerAccount("user1", "pw123456");
        repository.saveLoginState("user1", "ignored", true, "user1");
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.destroy();
        }
        idle();
        StreakDatabase.resetForTest();
        context.deleteDatabase("streak.db");
    }

    @Test
    public void rotation_restoresSelectedPage_notAlwaysHabits() {
        controller = Robolectric.buildActivity(DashboardActivity.class).setup();
        idle();

        // 切到「统计」页（非默认的习惯页）。
        bottomNav(controller.get()).setSelectedItemId(R.id.nav_stats);
        idle();

        // 旋转重建。
        controller.recreate();
        idle();

        DashboardActivity recreated = controller.get();
        assertEquals("旋转后应复原到统计页", R.id.nav_stats, bottomNav(recreated).getSelectedItemId());
        Fragment stats = recreated.getSupportFragmentManager().findFragmentByTag("stats");
        assertNotNull(stats);
        assertFalse("统计页应可见", stats.isHidden());
        Fragment habits = recreated.getSupportFragmentManager().findFragmentByTag("habits");
        assertNotNull(habits);
        assertTrue("习惯页应隐藏", habits.isHidden());
    }

    @Test
    public void freshLaunch_defaultsToHabitsPage() {
        controller = Robolectric.buildActivity(DashboardActivity.class).setup();
        idle();
        assertEquals(R.id.nav_habits, bottomNav(controller.get()).getSelectedItemId());
    }

    @Test
    public void rotation_whileLoggingOut_keepsNavigationDisabled() {
        controller = Robolectric.buildActivity(DashboardActivity.class).setup();
        idle();

        // 置退出进行中门闩（模拟已点退出）：状态挂在 Activity 作用域的共享 ViewModel 上，跨旋转存活。
        DashboardViewModel viewModel =
                new ViewModelProvider(controller.get()).get(DashboardViewModel.class);
        assertTrue(viewModel.beginLogout());

        controller.recreate();
        idle();

        DashboardActivity recreated = controller.get();
        assertFalse("退出进行中，旋转重建后底部导航应仍禁用",
                bottomNav(recreated).isEnabled());
        // 且门闩仍为 true——共享 ViewModel 未随 Activity 重建而复位。
        assertTrue(new ViewModelProvider(recreated).get(DashboardViewModel.class)
                .isLogoutInProgress());
    }

    private BottomNavigationView bottomNav(DashboardActivity activity) {
        return activity.findViewById(R.id.bottomNavigation);
    }

    private void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
