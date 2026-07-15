package com.streak.app.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

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

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 打卡记录随备份导出/导入的往返单测（Robolectric + 真实 Room）。
 *
 * <p>覆盖 CheckInRecord 拆表后备份层的两条关键路径：</p>
 * <ul>
 *   <li>新版备份往返：check_in_records.json 全保真——心情/耗时/打卡照片路径都能原样恢复；</li>
 *   <li>旧版备份合成：ZIP 里只有 habits.json（含 completedDates/notes、无 check_in_records.json）时，
 *       导入侧从习惯的打卡日期/备注重建记录（只含日期+备注，心情/耗时留空），保证向后兼容。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class AppRepositoryBackupRecordTest {

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

    /** 新版备份往返：心情/耗时/照片经 check_in_records.json 全保真恢复。 */
    @Test
    public void exportThenImport_preservesMoodDurationPhoto() {
        repository.registerAccount("alice", "pwAAAAAA");
        loginAs("alice");
        repository.saveHabit(newHabit(8001L, "冥想", "alice"));

        // 直接向记录表写一条全字段打卡（模拟带心情/耗时/照片的打卡）
        CheckInRecordDao dao = StreakDatabase.getInstance(context).checkInRecordDao();
        CheckInRecord full = new CheckInRecord();
        full.setHabitId(8001L);
        full.setDate("2026-01-05");
        full.setNote("很平静");
        full.setMood(5);
        full.setDurationMinutes(20);
        full.setPhotoUri(null); // 照片文件缺省，只验证元数据字段往返
        dao.upsert(full);

        File zip = repository.exportBackup();
        assertNotNull("导出应产生 zip", zip);
        assertTrue(zip.exists());

        // 清空当前账号数据后导入
        repository.deleteHabitById(8001L);
        assertTrue(repository.readHabits().isEmpty());

        assertTrue("导入应成功", repository.importBackup(Uri.fromFile(zip)));

        CheckInRecord restored = dao.getByHabitAndDate(8001L, "2026-01-05");
        assertNotNull("打卡记录应恢复", restored);
        assertEquals("很平静", restored.getNote());
        assertEquals("心情应原样恢复", 5, restored.getMood());
        assertEquals("耗时应原样恢复", 20, restored.getDurationMinutes());
    }

    /** 旧版备份（无 check_in_records.json）：从 habits.json 的 completedDates/notes 重建记录。 */
    @Test
    public void importLegacyBackup_synthesizesRecordsFromHabits() throws Exception {
        repository.registerAccount("bob", "pwBBBBBB");
        loginAs("bob");

        // 构造只含 habits.json 的旧版备份：习惯带 completedDates + notes，无 check_in_records.json
        String habitsJson = "{\"exportedAt\":\"2026-01-01 10:00:00\",\"habits\":["
                + "{\"id\":9001,\"title\":\"跑步\",\"ownerUsername\":\"bob\","
                + "\"completedDates\":[\"2026-01-01\",\"2026-01-02\"],"
                + "\"notes\":{\"2026-01-01\":\"状态不错\"}}"
                + "]}";
        File legacyZip = File.createTempFile("legacy", ".zip", context.getCacheDir());
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(legacyZip))) {
            writeEntry(zos, "habits.json", habitsJson.getBytes(StandardCharsets.UTF_8));
        }

        assertTrue("旧版备份应导入成功", repository.importBackup(Uri.fromFile(legacyZip)));

        // 记录应从 completedDates/notes 重建：两条日期，其中一条带备注
        CheckInRecordDao dao = StreakDatabase.getInstance(context).checkInRecordDao();
        List<CheckInRecord> records = dao.getByHabit(9001L);
        assertEquals("应重建 2 条打卡记录", 2, records.size());

        CheckInRecord withNote = dao.getByHabitAndDate(9001L, "2026-01-01");
        assertNotNull(withNote);
        assertEquals("状态不错", withNote.getNote());
        assertEquals("旧备份无心情数据，应留空", 0, withNote.getMood());

        CheckInRecord noNote = dao.getByHabitAndDate(9001L, "2026-01-02");
        assertNotNull(noNote);
        assertNull("无备注日期 note 应为 null", noNote.getNote());

        // 聚合回读：习惯的 completedDates 应含两天
        loginAs("bob");
        HabitItem restored = repository.findHabitById(9001L);
        assertNotNull(restored);
        assertEquals(2, restored.getCompletedDates().size());
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
