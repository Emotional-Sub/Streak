package com.streak.app.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import com.streak.app.db.StreakDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 备份导入的图片覆盖回滚单测（Robolectric + 真实 Room）。
 *
 * <p>覆盖修复点：导入时若某张图片与本机已有图片同名，覆盖前会先把原文件挪到
 * {@code .import_bak} 临时副本；导入失败时用副本还原、导入成功时删除副本。
 * 历史 bug 是同名旧图被 {@code FileOutputStream} 直接覆盖后无法恢复。</p>
 *
 * <p>制造「图片落地后才失败」的场景：让 accounts.json 是畸形 JSON（对象而非数组），
 * 图片先落地、随后账号解析抛异常，触发 importBackup 的 catch 回滚分支。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AppRepositoryImageRollbackTest {

    private Context context;
    private AppRepository repository;
    private File imageDir;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        StreakDatabase.resetForTest();
        context.deleteDatabase("streak.db");
        context.getSharedPreferences("java_streak_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        repository = new AppRepository(context);
        imageDir = new File(context.getFilesDir(), "habit_images");
        //noinspection ResultOfMethodCallIgnored
        imageDir.mkdirs();
    }

    @After
    public void tearDown() {
        StreakDatabase.resetForTest();
        context.deleteDatabase("streak.db");
    }

    @Test
    public void failedImport_restoresOverwrittenImage_andRemovesNewOne() throws Exception {
        // 本机已有一张 existing.jpg，内容为「原始」
        byte[] original = "ORIGINAL-IMAGE-BYTES".getBytes(StandardCharsets.UTF_8);
        File existing = new File(imageDir, "existing.jpg");
        writeFile(existing, original);

        // 构造一个「图片能落地、但随后失败」的坏备份：
        // - habits.json 合法（能解析出 HabitBackup）
        // - images/existing.jpg 会覆盖同名旧图
        // - images/brandnew.jpg 是本机原本没有的新图
        // - accounts.json 是畸形 JSON（对象而非数组），在图片落地后解析抛异常触发回滚
        File badZip = File.createTempFile("rollback", ".zip", context.getCacheDir());
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(badZip))) {
            writeEntry(zos, "habits.json",
                    "{\"exportedAt\":\"2026-01-01 10:00:00\",\"habits\":[]}"
                            .getBytes(StandardCharsets.UTF_8));
            writeEntry(zos, "images/existing.jpg",
                    "NEW-IMAGE-BYTES".getBytes(StandardCharsets.UTF_8));
            writeEntry(zos, "images/brandnew.jpg",
                    "BRAND-NEW-BYTES".getBytes(StandardCharsets.UTF_8));
            // 畸形：期望是 List<UserAccount>，这里给一个对象 -> Gson 解析抛异常
            writeEntry(zos, "accounts.json", "{}".getBytes(StandardCharsets.UTF_8));
        }

        assertFalse("坏备份应导入失败", repository.importBackup(Uri.fromFile(badZip)));

        // 同名旧图应被还原为原始内容（而非停留在被覆盖的新内容）
        assertTrue("原图应仍在", existing.exists());
        assertArrayEquals("同名旧图应被还原为原始内容", original, readFile(existing));

        // 本次新写入的图片应被删除（不留孤儿）
        assertFalse("新图应被回滚删除", new File(imageDir, "brandnew.jpg").exists());

        // 不应残留临时副本
        assertFalse("不应残留 .import_bak 临时副本",
                new File(imageDir, "existing.jpg.import_bak").exists());
    }

    @Test
    public void successfulImport_overwritesImage_andLeavesNoTempBackup() throws Exception {
        byte[] original = "ORIGINAL".getBytes(StandardCharsets.UTF_8);
        File existing = new File(imageDir, "pic.jpg");
        writeFile(existing, original);

        byte[] updated = "UPDATED-CONTENT".getBytes(StandardCharsets.UTF_8);
        File goodZip = File.createTempFile("good", ".zip", context.getCacheDir());
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(goodZip))) {
            writeEntry(zos, "habits.json",
                    "{\"exportedAt\":\"2026-01-01 10:00:00\",\"habits\":[]}"
                            .getBytes(StandardCharsets.UTF_8));
            writeEntry(zos, "images/pic.jpg", updated);
        }

        assertTrue("合法备份应导入成功", repository.importBackup(Uri.fromFile(goodZip)));

        // 成功导入后图片被更新，且不留临时副本
        assertArrayEquals("成功导入后同名图应为新内容", updated, readFile(existing));
        assertFalse("成功后不应残留 .import_bak",
                new File(imageDir, "pic.jpg.import_bak").exists());
    }

    // ---- 辅助 ----

    private void writeEntry(ZipOutputStream zos, String name, byte[] data) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private void writeFile(File file, byte[] data) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }
    }

    private byte[] readFile(File file) throws Exception {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = fis.read(buf)) != -1) {
                baos.write(buf, 0, r);
            }
            return baos.toByteArray();
        }
    }
}
