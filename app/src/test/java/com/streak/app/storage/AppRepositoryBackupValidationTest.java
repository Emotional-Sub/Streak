package com.streak.app.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import com.streak.app.db.StreakDatabase;
import com.streak.app.model.HabitItem;
import com.streak.app.model.UserAccount;
import com.streak.app.util.PasswordHasher;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
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

    @Test
    public void legacyPlaintextAccount_importsAsHash_andCanLogin() throws Exception {
        File zip = zip(
                entry("habits.json", "{\"exportedAt\":\"2026-01-01\",\"habits\":[]}"),
                entry("accounts.json",
                        "[{\"username\":\"legacy\",\"password\":\"legacy-secret\"}]"));

        assertTrue(repository.importBackup(Uri.fromFile(zip)));
        UserAccount restored = repository.getAccount("legacy");
        assertNotNull(restored);
        assertNull(restored.getPassword());
        assertTrue(PasswordHasher.isValidStoredCredential(
                restored.getPasswordHash(), restored.getSalt()));
        assertTrue(repository.validateLogin("legacy", "legacy-secret"));
    }

    @Test
    public void incompleteOrCorruptCredentialWithPlaintext_isRejected() throws Exception {
        String[] accounts = {
                "[{\"username\":\"bad1\",\"passwordHash\":\"abc\",\"password\":\"pw\"}]",
                "[{\"username\":\"bad2\",\"salt\":\"abc\",\"password\":\"pw\"}]",
                "[{\"username\":\"bad3\",\"passwordHash\":\"   \",\"password\":\"pw\"}]",
                "[{\"username\":\"bad4\",\"passwordHash\":\"abc\",\"salt\":\"def\","
                        + "\"password\":\"pw\"}]"
        };

        for (String accountJson : accounts) {
            seedHabit();
            File zip = zip(
                    entry("habits.json", "{\"exportedAt\":\"2026-01-01\",\"habits\":[]}"),
                    entry("accounts.json", accountJson));

            assertFalse(repository.importBackup(Uri.fromFile(zip)));
            assertEquals(1, repository.readHabits().size());
        }
    }

    @Test
    public void duplicateMetadataEntry_isRejectedWithoutChangingData() throws Exception {
        seedHabit();
        File zip = duplicateZip("habits.json",
                "{\"exportedAt\":\"first\",\"habits\":[]}".getBytes(StandardCharsets.UTF_8),
                "{\"exportedAt\":\"second\",\"habits\":[]}".getBytes(StandardCharsets.UTF_8));

        assertFalse(repository.importBackup(Uri.fromFile(zip)));
        assertEquals(1, repository.readHabits().size());
    }

    @Test
    public void ownerlessHabitWithoutStudentAccount_isRejected() throws Exception {
        File zip = zip(
                entry("habits.json", "{\"exportedAt\":\"2026-01-01\",\"habits\":["
                        + "{\"id\":9201,\"title\":\"orphan\"}]}"),
                entry("accounts.json",
                        "[{\"username\":\"alice\",\"password\":\"pwAAAAAA\"}]"));

        assertFalse(repository.importBackup(Uri.fromFile(zip)));
        assertNull(StreakDatabase.getInstance(context).habitDao().findById(9201L));
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

    /** JDK ZipOutputStream 主动拒绝重名条目，这里按 ZIP 规范手工构造恶意重复项。 */
    private File duplicateZip(String name, byte[] first, byte[] second) throws Exception {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        int firstOffset = bytes.size();
        writeStoredLocal(output, nameBytes, first);
        int secondOffset = bytes.size();
        writeStoredLocal(output, nameBytes, second);
        int centralOffset = bytes.size();
        writeStoredCentral(output, nameBytes, first, firstOffset);
        writeStoredCentral(output, nameBytes, second, secondOffset);
        int centralSize = bytes.size() - centralOffset;
        writeLeInt(output, 0x06054b50);
        writeLeShort(output, 0);
        writeLeShort(output, 0);
        writeLeShort(output, 2);
        writeLeShort(output, 2);
        writeLeInt(output, centralSize);
        writeLeInt(output, centralOffset);
        writeLeShort(output, 0);
        output.flush();

        File file = File.createTempFile("duplicate-entry", ".zip", context.getCacheDir());
        try (FileOutputStream fileOutput = new FileOutputStream(file)) {
            fileOutput.write(bytes.toByteArray());
        }
        return file;
    }

    private void writeStoredLocal(DataOutputStream output, byte[] name, byte[] data)
            throws IOException {
        writeLeInt(output, 0x04034b50);
        writeLeShort(output, 20);
        writeLeShort(output, 0);
        writeLeShort(output, 0);
        writeLeShort(output, 0);
        writeLeShort(output, 0);
        writeLeInt(output, crc(data));
        writeLeInt(output, data.length);
        writeLeInt(output, data.length);
        writeLeShort(output, name.length);
        writeLeShort(output, 0);
        output.write(name);
        output.write(data);
    }

    private void writeStoredCentral(DataOutputStream output, byte[] name, byte[] data, int offset)
            throws IOException {
        writeLeInt(output, 0x02014b50);
        writeLeShort(output, 20);
        writeLeShort(output, 20);
        writeLeShort(output, 0);
        writeLeShort(output, 0);
        writeLeShort(output, 0);
        writeLeShort(output, 0);
        writeLeInt(output, crc(data));
        writeLeInt(output, data.length);
        writeLeInt(output, data.length);
        writeLeShort(output, name.length);
        writeLeShort(output, 0);
        writeLeShort(output, 0);
        writeLeShort(output, 0);
        writeLeShort(output, 0);
        writeLeInt(output, 0);
        writeLeInt(output, offset);
        output.write(name);
    }

    private int crc(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return (int) crc.getValue();
    }

    private void writeLeShort(DataOutputStream output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private void writeLeInt(DataOutputStream output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
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
