package com.streak.app.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import com.streak.app.db.StreakDatabase;
import com.streak.app.model.HabitItem;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 备份导入的事务性 / 失败回滚单测（Robolectric + 真实 Room）。
 *
 * <p>覆盖 Phase 1a 关注点：</p>
 * <ul>
 *   <li>正常往返：exportBackup 产出的 zip 能被 importBackup 完整还原；</li>
 *   <li>缺 habits.json：直接拒绝，现有习惯分毫不动（不半途清库）；</li>
 *   <li>habits.json 损坏：解析失败即放弃，现有数据保持不变。</li>
 * </ul>
 *
 * <p>每个用例前重置单例与磁盘库，保证互不串数据（同 {@link AppRepositoryTest}）。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AppRepositoryBackupTest {

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

    // ---- 正常往返 ----

    @Test
    public void exportThenImport_restoresHabits() {
        repository.registerAccount("alice", "pwAAAAAA");
        loginAs("alice");
        repository.saveHabit(newHabit(5001L, "跑步", "alice"));
        repository.saveHabit(newHabit(5002L, "读书", "alice"));

        File zip = repository.exportBackup();
        assertNotNull("导出应产出 zip 文件", zip);
        assertTrue(zip.exists());

        // 清空当前账号习惯，再导入，应完整恢复
        repository.deleteHabitById(5001L);
        repository.deleteHabitById(5002L);
        assertTrue(repository.readHabits().isEmpty());

        assertTrue("导入应成功", repository.importBackup(Uri.fromFile(zip)));
        loginAs("alice");
        assertEquals(2, repository.readHabits().size());
    }

    // ---- 缺 habits.json：拒绝且不动现有数据 ----

    @Test
    public void importMissingHabitsJson_keepsExistingDataUntouched() throws Exception {
        repository.registerAccount("bob", "pwBBBBBB");
        loginAs("bob");
        repository.saveHabit(newHabit(6001L, "冥想", "bob"));

        // 造一个只含无关条目、没有 habits.json 的 zip
        File badZip = File.createTempFile("no_habits", ".zip", context.getCacheDir());
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(badZip))) {
            writeEntry(zos, "readme.txt", "not a real backup".getBytes(StandardCharsets.UTF_8));
        }

        assertFalse("缺 habits.json 应导入失败", repository.importBackup(Uri.fromFile(badZip)));
        // 现有习惯不受影响
        assertEquals(1, repository.readHabits().size());
        assertEquals("冥想", repository.readHabits().get(0).getTitle());
    }

    // ---- habits.json 损坏：解析失败即放弃，现有数据保持 ----

    @Test
    public void importCorruptHabitsJson_rollsBackAndKeepsExistingData() throws Exception {
        repository.registerAccount("carol", "pwCCCCCC");
        loginAs("carol");
        repository.saveHabit(newHabit(7001L, "写作", "carol"));

        File badZip = File.createTempFile("corrupt", ".zip", context.getCacheDir());
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(badZip))) {
            // habits.json 内容是坏 JSON，无法解析成 HabitBackup
            writeEntry(zos, "habits.json", "{ this is not valid json ]".getBytes(StandardCharsets.UTF_8));
        }

        assertFalse("坏 habits.json 应导入失败", repository.importBackup(Uri.fromFile(badZip)));
        // 现有习惯保持不变，未被半途清空
        assertEquals(1, repository.readHabits().size());
        assertEquals("写作", repository.readHabits().get(0).getTitle());
    }

    // ---- 辅助 ----

    private void writeEntry(ZipOutputStream zos, String name, byte[] data) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

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
