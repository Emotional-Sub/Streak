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
 * <p>制造「部分图片落地后才失败」的场景：前两个条目先覆盖/新建成功，第三个条目因
 * 无法创建 {@code .import_bak} 副本而抛异常，触发 importBackup 的 catch 回滚分支。</p>
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

        File blocked = new File(imageDir, "blocked.jpg");
        byte[] blockedOriginal = "BLOCKED-ORIGINAL".getBytes(StandardCharsets.UTF_8);
        writeFile(blocked, blockedOriginal);
        File blockingBak = new File(imageDir, "blocked.jpg.import_bak");
        //noinspection ResultOfMethodCallIgnored
        blockingBak.mkdirs();
        writeFile(new File(blockingBak, "sentinel"), "x".getBytes(StandardCharsets.UTF_8));

        // 构造一个结构合法、图片能落地的备份：
        // - habits.json 合法（能解析出 HabitBackup）
        // - images/existing.jpg 会覆盖同名旧图
        // - images/brandnew.jpg 是本机原本没有的新图
        // - images/blocked.jpg 第三个处理，因副本目标被非空目录占用而失败
        File badZip = File.createTempFile("rollback", ".zip", context.getCacheDir());
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(badZip))) {
            writeEntry(zos, "habits.json",
                    "{\"exportedAt\":\"2026-01-01 10:00:00\",\"habits\":[]}"
                            .getBytes(StandardCharsets.UTF_8));
            writeEntry(zos, "images/existing.jpg",
                    "NEW-IMAGE-BYTES".getBytes(StandardCharsets.UTF_8));
            writeEntry(zos, "images/brandnew.jpg",
                    "BRAND-NEW-BYTES".getBytes(StandardCharsets.UTF_8));
            writeEntry(zos, "images/blocked.jpg",
                    "MUST-NOT-BE-WRITTEN".getBytes(StandardCharsets.UTF_8));
        }

        assertFalse("部分图片写入后失败时导入应失败", repository.importBackup(Uri.fromFile(badZip)));

        // 同名旧图应被还原为原始内容（而非停留在被覆盖的新内容）
        assertTrue("原图应仍在", existing.exists());
        assertArrayEquals("同名旧图应被还原为原始内容", original, readFile(existing));

        // 本次新写入的图片应被删除（不留孤儿）
        assertFalse("新图应被回滚删除", new File(imageDir, "brandnew.jpg").exists());
        assertArrayEquals("失败条目原图不得被覆盖", blockedOriginal, readFile(blocked));

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

    /**
     * 备份副本无法创建时必须中止导入，绝不覆盖同名旧图。
     *
     * <p>历史 bug：{@code renameTo(bak)} 失败时代码仍继续 {@code FileOutputStream} 覆盖原图，
     * 且没记录可回滚副本——一旦后续导入失败，旧图永久丢失。</p>
     *
     * <p>制造 renameTo 失败：在 {@code .import_bak} 目标路径预先放一个「非空目录」，
     * 使 {@code bak.delete()} 与 {@code out.renameTo(bak)} 都失败（无法覆盖非空目录）。
     * 此时导入应立即中止、返回 false，且同名旧图保持原始内容不被破坏。</p>
     */
    @Test
    public void backupCopyFailure_abortsImport_andKeepsOriginalIntact() throws Exception {
        byte[] original = "MUST-SURVIVE".getBytes(StandardCharsets.UTF_8);
        File existing = new File(imageDir, "keep.jpg");
        writeFile(existing, original);

        // 在 .import_bak 目标路径放一个非空目录，令 delete() 和 renameTo() 都失败
        File blockingBak = new File(imageDir, "keep.jpg.import_bak");
        //noinspection ResultOfMethodCallIgnored
        blockingBak.mkdirs();
        writeFile(new File(blockingBak, "sentinel"), "x".getBytes(StandardCharsets.UTF_8));

        File zip = File.createTempFile("copyfail", ".zip", context.getCacheDir());
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            writeEntry(zos, "habits.json",
                    "{\"exportedAt\":\"2026-01-01 10:00:00\",\"habits\":[]}"
                            .getBytes(StandardCharsets.UTF_8));
            writeEntry(zos, "images/keep.jpg",
                    "SHOULD-NOT-BE-WRITTEN".getBytes(StandardCharsets.UTF_8));
        }

        assertFalse("无法创建可回滚副本时导入应中止", repository.importBackup(Uri.fromFile(zip)));

        // 关键断言：同名旧图未被覆盖，内容仍是原始字节
        assertTrue("原图应仍在", existing.exists());
        assertArrayEquals("副本创建失败时绝不能覆盖同名旧图", original, readFile(existing));
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
