package com.streak.app.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

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

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 备份信封和散装实体的结构/引用校验测试。 */
@RunWith(RobolectricTestRunner.class)
public class AppRepositoryBackupValidationTest {

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
    public void envelopeMissingRequiredLists_isRejectedWithoutChangingData() throws Exception {
        seedHabit();
        File zip = zip(entry("backup.json",
                "{\"schemaVersion\":4,\"habits\":[]}"));

        assertFalse(repository.importBackup(Uri.fromFile(zip)));
        assertEquals(1, repository.readHabits().size());
    }

    @Test
    public void unsupportedEnvelopeVersion_isRejectedWithoutLegacyFallback() throws Exception {
        seedHabit();
        File zip = zip(entry("backup.json",
                "{\"schemaVersion\":3,\"habits\":[],\"checkInRecords\":[],\"accounts\":[]}"));

        assertFalse(repository.importBackup(Uri.fromFile(zip)));
        assertEquals(1, repository.readHabits().size());
    }

    @Test
    public void semanticallyInvalidRecordList_isRejected() throws Exception {
        seedHabit();
        File zip = zip(
                entry("habits.json", "{\"exportedAt\":\"2026-01-01\",\"habits\":[]}"),
                entry("check_in_records.json", "[{}]"));

        assertFalse(repository.importBackup(Uri.fromFile(zip)));
        assertEquals(1, repository.readHabits().size());
    }

    @Test
    public void orphanRecord_isRejected() throws Exception {
        seedHabit();
        File zip = zip(
                entry("habits.json", "{\"exportedAt\":\"2026-01-01\",\"habits\":[]}"),
                entry("check_in_records.json",
                        "[{\"habitId\":999999,\"date\":\"2026-01-01\"}]"));

        assertFalse(repository.importBackup(Uri.fromFile(zip)));
        assertEquals(1, repository.readHabits().size());
    }

    @Test
    public void semanticallyInvalidAccountList_isRejected() throws Exception {
        seedHabit();
        File zip = zip(
                entry("habits.json", "{\"exportedAt\":\"2026-01-01\",\"habits\":[]}"),
                entry("accounts.json", "[{}]"));

        assertFalse(repository.importBackup(Uri.fromFile(zip)));
        assertEquals(1, repository.readHabits().size());
    }

    private void seedHabit() {
        repository.registerAccount("alice", "pwAAAAAA");
        repository.saveLoginState("alice", "ignored", true, "alice");
        HabitItem habit = new HabitItem(9101L, "阅读", "内容", "20:00",
                "2026-01-01 10:00", null, "学习", new ArrayList<>(),
                new ArrayList<>(), true);
        habit.setOwnerUsername("alice");
        repository.saveHabit(habit);
    }

    private File zip(ZipEntryData... entries) throws Exception {
        File file = File.createTempFile("backup-validation", ".zip", context.getCacheDir());
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(file))) {
            for (ZipEntryData entry : entries) {
                zos.putNextEntry(new ZipEntry(entry.name));
                zos.write(entry.data.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return file;
    }

    private ZipEntryData entry(String name, String data) {
        return new ZipEntryData(name, data);
    }

    private static final class ZipEntryData {
        private final String name;
        private final String data;

        private ZipEntryData(String name, String data) {
            this.name = name;
            this.data = data;
        }
    }
}
