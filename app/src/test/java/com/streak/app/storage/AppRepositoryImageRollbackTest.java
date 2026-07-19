package com.streak.app.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import com.google.gson.Gson;
import com.streak.app.data.BackupService;
import com.streak.app.data.CheckInRepository;
import com.streak.app.data.ImageStore;
import com.streak.app.data.ReminderManager;
import com.streak.app.data.UserRepository;
import com.streak.app.db.CheckInRecordDao;
import com.streak.app.db.HabitDao;
import com.streak.app.db.StreakDatabase;
import com.streak.app.model.CheckInRecord;
import com.streak.app.model.HabitBackup;
import com.streak.app.model.HabitItem;
import com.streak.app.model.UserAccount;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** End-to-end image import rollback tests backed by the real Room database. */
@RunWith(RobolectricTestRunner.class)
public class AppRepositoryImageRollbackTest {

    private static final long LOCAL_HABIT_ID = 1001L;
    private static final String LOCAL_HABIT_TITLE = "local-before-import";

    private final Gson gson = new Gson();

    private Context context;
    private StreakDatabase database;
    private HabitDao habitDao;
    private CheckInRecordDao checkInRecordDao;
    private File imageDir;
    private File exportDir;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        StreakDatabase.resetForTest();
        context.deleteDatabase("streak.db");
        context.getSharedPreferences("java_streak_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();

        imageDir = new File(context.getFilesDir(), "habit_images");
        exportDir = new File(context.getFilesDir(), "exports");
        deleteRecursively(imageDir);
        deleteRecursively(exportDir);
        assertTrue(imageDir.mkdirs() || imageDir.isDirectory());
        assertTrue(exportDir.mkdirs() || exportDir.isDirectory());

        database = StreakDatabase.getInstance(context);
        habitDao = database.habitDao();
        checkInRecordDao = database.checkInRecordDao();

        UserAccount student = new UserAccount("student", "local-password");
        database.userDao().upsert(student);
        habitDao.upsert(habit(LOCAL_HABIT_ID, LOCAL_HABIT_TITLE, null));

        CheckInRecord existingRecord = new CheckInRecord();
        existingRecord.setHabitId(LOCAL_HABIT_ID);
        existingRecord.setDate("2026-01-01");
        existingRecord.setNote("local-note");
        checkInRecordDao.upsert(existingRecord);
    }

    @After
    public void tearDown() {
        StreakDatabase.resetForTest();
        context.deleteDatabase("streak.db");
        deleteRecursively(imageDir);
        deleteRecursively(exportDir);
    }

    @Test
    public void failedImageWrite_restoresOldImage_removesNewImages_keepsDatabase_andCleansRollbackDirectory()
            throws Exception {
        File existing = new File(imageDir, "existing.jpg");
        byte[] original = bytes("ORIGINAL-IMAGE");
        writeFile(existing, original);

        File brandNew = new File(imageDir, "brandnew.jpg");
        File partiallyWritten = new File(imageDir, "partial.jpg");
        File zip = zip(
                habitsEntry(
                        habit(2001L, "import-existing", imageReference("existing.jpg")),
                        habit(2002L, "import-new", imageReference("brandnew.jpg")),
                        habit(2003L, "import-partial", imageReference("partial.jpg"))),
                fileEntry("images/existing.jpg", bytes("REPLACEMENT")),
                fileEntry("images/brandnew.jpg", bytes("BRAND-NEW")),
                fileEntry("images/partial.jpg", bytes("PARTIAL-WRITE-TARGET")));

        InjectedBackupService service = newService(3, false);

        assertFalse(service.importBackup(Uri.fromFile(zip)));
        assertEquals(3, service.writeAttempts);
        assertArrayEquals(original, readFile(existing));
        assertFalse("new image must be deleted during rollback", brandNew.exists());
        assertFalse("partially written image must be deleted during rollback",
                partiallyWritten.exists());
        assertDatabaseUnchanged();
        assertNotNull(service.createdRollbackDirectory);
        assertFalse("rollback directory must be deleted after a complete rollback",
                service.createdRollbackDirectory.exists());
        assertNoTemporaryImportArtifacts();
    }

    @Test
    public void rollbackDirectoryCreationFailure_abortsBeforeOverwrite_andKeepsDatabase()
            throws Exception {
        File existing = new File(imageDir, "keep.jpg");
        byte[] original = bytes("MUST-SURVIVE");
        writeFile(existing, original);
        File zip = zip(
                habitsEntry(habit(2101L, "replacement", imageReference("keep.jpg"))),
                fileEntry("images/keep.jpg", bytes("MUST-NOT-BE-WRITTEN")));

        InjectedBackupService service = newService(0, true);

        assertFalse(service.importBackup(Uri.fromFile(zip)));
        assertEquals(1, service.rollbackDirectoryAttempts);
        assertEquals("image writing must not start without a rollback copy", 0,
                service.writeAttempts);
        assertArrayEquals(original, readFile(existing));
        assertDatabaseUnchanged();
        assertNoTemporaryImportArtifacts();
    }

    @Test
    public void successfulOverwrite_replacesImage_andCleansRollbackDirectory() throws Exception {
        File existing = new File(imageDir, "pic.jpg");
        writeFile(existing, bytes("ORIGINAL"));
        byte[] updated = bytes("UPDATED-CONTENT");
        File zip = zip(
                habitsEntry(habit(2201L, "imported", imageReference("pic.jpg"))),
                fileEntry("images/pic.jpg", updated));

        InjectedBackupService service = newService(0, false);

        assertTrue(service.importBackup(Uri.fromFile(zip)));
        assertArrayEquals(updated, readFile(existing));
        assertEquals(1, service.writeAttempts);
        assertNotNull(service.createdRollbackDirectory);
        assertFalse(service.createdRollbackDirectory.exists());
        HabitItem imported = habitDao.findById(2201L);
        assertNotNull(imported);
        assertEquals(Uri.fromFile(existing).toString(), imported.getImageUri());
        assertNoTemporaryImportArtifacts();
    }

    @Test
    public void reservedRollbackArtifactNames_areRejectedBeforeWrites() throws Exception {
        String[] reservedNames = {
                "photo.IMPORT_BAK",
                ".Streak-Import-forged",
                ".STREAK-RESTORE-forged"
        };

        for (String reservedName : reservedNames) {
            File zip = zip(
                    habitsEntry(),
                    fileEntry("images/" + reservedName, bytes("ATTACK")));
            InjectedBackupService service = newService(0, false);

            assertFalse("reserved image name must be rejected: " + reservedName,
                    service.importBackup(Uri.fromFile(zip)));
            assertEquals(0, service.writeAttempts);
            assertDatabaseUnchanged();
            assertNoTemporaryImportArtifacts();
        }
    }

    @Test
    public void caseInsensitiveDuplicateImageEntries_areRejectedBeforeWrites() throws Exception {
        File zip = zip(
                habitsEntry(habit(2301L, "duplicate", imageReference("Photo.JPG"))),
                fileEntry("images/Photo.JPG", bytes("FIRST")),
                fileEntry("images/photo.jpg", bytes("SECOND")));
        InjectedBackupService service = newService(0, false);

        assertFalse(service.importBackup(Uri.fromFile(zip)));
        assertEquals(0, service.writeAttempts);
        assertFalse(new File(imageDir, "Photo.JPG").exists());
        assertFalse(new File(imageDir, "photo.jpg").exists());
        assertDatabaseUnchanged();
        assertNoTemporaryImportArtifacts();
    }

    @Test
    public void imageDirectoryEntries_areRejectedBeforeWrites() throws Exception {
        String[] directoryNames = {"images/", "images/nested/"};

        for (String directoryName : directoryNames) {
            File zip = zip(habitsEntry(), directoryEntry(directoryName));
            InjectedBackupService service = newService(0, false);

            assertFalse("image directory entry must be rejected: " + directoryName,
                    service.importBackup(Uri.fromFile(zip)));
            assertEquals(0, service.writeAttempts);
            assertDatabaseUnchanged();
            assertNoTemporaryImportArtifacts();
        }
    }

    @Test
    public void unreferencedImage_doesNotOverwriteLocalFile() throws Exception {
        File local = new File(imageDir, "unreferenced.jpg");
        byte[] original = bytes("LOCAL-ORIGINAL");
        writeFile(local, original);
        File zip = zip(
                habitsEntry(),
                fileEntry("images/unreferenced.jpg", bytes("UNREFERENCED-REPLACEMENT")));
        InjectedBackupService service = newService(0, false);

        assertFalse(service.importBackup(Uri.fromFile(zip)));
        assertEquals(0, service.writeAttempts);
        assertArrayEquals(original, readFile(local));
        assertDatabaseUnchanged();
        assertNoTemporaryImportArtifacts();
    }

    @Test
    public void missingImage_doesNotBindHabitToLocalSameNameFile() throws Exception {
        File local = new File(imageDir, "missing.jpg");
        byte[] original = bytes("LOCAL-SAME-NAME-IMAGE");
        writeFile(local, original);
        File zip = zip(
                habitsEntry(habit(2401L, "missing-image", imageReference("missing.jpg"))));
        InjectedBackupService service = newService(0, false);

        assertTrue(service.importBackup(Uri.fromFile(zip)));
        assertEquals(0, service.writeAttempts);
        HabitItem imported = habitDao.findById(2401L);
        assertNotNull(imported);
        assertNull("a missing ZIP image must not bind to a local same-name file",
                imported.getImageUri());
        assertArrayEquals(original, readFile(local));
        assertNoTemporaryImportArtifacts();
    }

    private InjectedBackupService newService(int failOnWriteAttempt,
                                             boolean failRollbackDirectoryCreation) {
        ImageStore imageStore = new ImageStore(context, imageDir);
        CheckInRepository checkInRepository =
                new CheckInRepository(checkInRecordDao, imageStore);
        UserRepository userRepository = new UserRepository(
                database, database.userDao(), habitDao, () -> "student");
        return new InjectedBackupService(
                context,
                database,
                habitDao,
                checkInRecordDao,
                imageStore,
                checkInRepository,
                userRepository,
                new NoOpReminderManager(context),
                imageDir,
                exportDir,
                failOnWriteAttempt,
                failRollbackDirectoryCreation);
    }

    private HabitItem habit(long id, String title, String imageUri) {
        HabitItem item = new HabitItem(
                id,
                title,
                "content",
                "20:00",
                "2026-01-01 10:00",
                imageUri,
                "test",
                new ArrayList<>(),
                new ArrayList<>(),
                false);
        item.setOwnerUsername("student");
        return item;
    }

    private String imageReference(String fileName) {
        return "/backup-source/" + fileName;
    }

    private ZipEntryData habitsEntry(HabitItem... habits) {
        HabitBackup backup = new HabitBackup(
                "2026-01-01 10:00:00", Arrays.asList(habits));
        return fileEntry("habits.json",
                gson.toJson(backup).getBytes(StandardCharsets.UTF_8));
    }

    private ZipEntryData fileEntry(String name, byte[] data) {
        return new ZipEntryData(name, data);
    }

    private ZipEntryData directoryEntry(String name) {
        return new ZipEntryData(name, new byte[0]);
    }

    private File zip(ZipEntryData... entries) throws Exception {
        File file = File.createTempFile("image-import", ".zip", context.getCacheDir());
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
            for (ZipEntryData entry : entries) {
                output.putNextEntry(new ZipEntry(entry.name));
                output.write(entry.data);
                output.closeEntry();
            }
        }
        return file;
    }

    private void assertDatabaseUnchanged() {
        List<HabitItem> habits = habitDao.getAll();
        assertEquals(1, habits.size());
        assertEquals(LOCAL_HABIT_ID, habits.get(0).getId());
        assertEquals(LOCAL_HABIT_TITLE, habits.get(0).getTitle());

        CheckInRecord record = checkInRecordDao.getByHabitAndDate(
                LOCAL_HABIT_ID, "2026-01-01");
        assertNotNull(record);
        assertEquals("local-note", record.getNote());
        assertEquals(1, database.userDao().count());
    }

    private void assertNoTemporaryImportArtifacts() {
        File[] children = imageDir.listFiles();
        assertNotNull(children);
        for (File child : children) {
            String normalizedName = child.getName().toLowerCase(Locale.ROOT);
            assertFalse("temporary import artifact remains: " + child,
                    normalizedName.startsWith(".streak-import-")
                            || normalizedName.startsWith(".streak-restore-")
                            || normalizedName.endsWith(".import_bak"));
        }
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private void writeFile(File file, byte[] data) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(data);
        }
    }

    private byte[] readFile(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static final class ZipEntryData {
        private final String name;
        private final byte[] data;

        private ZipEntryData(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }

    private static final class NoOpReminderManager extends ReminderManager {
        private NoOpReminderManager(Context context) {
            super(context);
        }

        @Override
        public void cancel(long habitId) {
            // Alarm scheduling is outside the image import contract under test.
        }
    }

    private static final class InjectedBackupService extends BackupService {
        private final int failOnWriteAttempt;
        private final boolean failRollbackDirectoryCreation;
        private int writeAttempts;
        private int rollbackDirectoryAttempts;
        private File createdRollbackDirectory;

        private InjectedBackupService(Context context,
                                      StreakDatabase database,
                                      HabitDao habitDao,
                                      CheckInRecordDao checkInRecordDao,
                                      ImageStore imageStore,
                                      CheckInRepository checkInRepository,
                                      UserRepository userRepository,
                                      ReminderManager reminderManager,
                                      File imageDir,
                                      File exportDir,
                                      int failOnWriteAttempt,
                                      boolean failRollbackDirectoryCreation) {
            super(context, database, habitDao, checkInRecordDao, imageStore,
                    checkInRepository, userRepository, reminderManager,
                    imageDir, exportDir, () -> {
                    });
            this.failOnWriteAttempt = failOnWriteAttempt;
            this.failRollbackDirectoryCreation = failRollbackDirectoryCreation;
        }

        @Override
        protected File createImportBackupDir() throws IOException {
            rollbackDirectoryAttempts++;
            if (failRollbackDirectoryCreation) {
                throw new IOException("injected rollback directory creation failure");
            }
            createdRollbackDirectory = super.createImportBackupDir();
            return createdRollbackDirectory;
        }

        @Override
        protected void writeImportedImage(File out, byte[] data) throws IOException {
            writeAttempts++;
            if (writeAttempts == failOnWriteAttempt) {
                try (FileOutputStream output = new FileOutputStream(out)) {
                    if (data.length > 0) {
                        output.write(data, 0, Math.min(4, data.length));
                    }
                }
                throw new IOException("injected image write failure");
            }
            super.writeImportedImage(out, data);
        }
    }
}
