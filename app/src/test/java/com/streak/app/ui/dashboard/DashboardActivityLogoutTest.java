package com.streak.app.ui.dashboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import com.streak.app.db.StreakDatabase;
import com.streak.app.ui.MainActivity;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;

/** 退出导航不依赖旧 Activity 生命周期的契约测试。 */
@RunWith(RobolectricTestRunner.class)
public class DashboardActivityLogoutTest {

    @After
    public void tearDown() {
        StreakDatabase.resetForTest();
    }

    @Test
    public void launchLoginFromApplicationContext_clearsDashboardTask() {
        Context context = ApplicationProvider.getApplicationContext();

        DashboardActivity.launchLogin(context);

        Intent intent = Shadows.shadowOf((Application) context).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(MainActivity.class.getName(), intent.getComponent().getClassName());
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0);
    }

    @Test
    public void viewModelLogoutGate_rejectsDuplicateRequests() {
        Application application = ApplicationProvider.getApplicationContext();
        DashboardViewModel viewModel = new DashboardViewModel(application);

        assertTrue(viewModel.beginLogout());
        assertTrue(viewModel.isLogoutInProgress());
        assertFalse(viewModel.beginLogout());
    }
}
