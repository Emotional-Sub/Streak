package com.streak.app.data;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import com.streak.app.model.CameraCaptureInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 图片存储与相册交互（Phase B 从 {@code AppRepository} 拆出）。
 *
 * <p><b>职责边界。</b>只负责图片文件的复制、删除、相册保存、分享缓存，以及备份 ZIP 里图片条目的写入
 * 与文件定位。不碰习惯/账号/打卡的业务数据——那些属于各自的 Repository。</p>
 *
 * <p>所有方法只依赖 {@link Context} 与应用私有的 {@code habit_images} 目录，故可独立成类、独立测试。
 * {@code AppRepository} 保留同名公开方法转发到本类，UI 层零改动。</p>
 */
public class ImageStore {

    private final Context context;
    private final File imageDir;

    public ImageStore(Context context, File imageDir) {
        this.context = context.getApplicationContext();
        this.imageDir = imageDir;
    }

    /**
     * 把拍照/相册得到的图片复制进头像目录，返回 file:// uri。
     */
    public String copyAvatarImage(Uri uri) {
        return copyInto(uri, "avatar_");
    }

    public String copyGalleryImage(Uri uri) {
        return copyInto(uri, "gallery_");
    }

    /** 把外部 uri 的图片按扩展名复制进 imageDir，文件名带前缀 + 时间戳。失败返回 null。 */
    private String copyInto(Uri uri, String prefix) {
        try {
            String extension = "jpg";
            String mimeType = context.getContentResolver().getType(uri);
            if (mimeType != null && mimeType.contains("/")) {
                extension = mimeType.substring(mimeType.lastIndexOf('/') + 1);
            }
            File target = new File(imageDir, prefix + System.currentTimeMillis() + "." + extension);
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(target)) {
                if (input == null) {
                    return null;
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
            return Uri.fromFile(target).toString();
        } catch (Exception e) {
            return null;
        }
    }

    public CameraCaptureInfo createCameraCapture() {
        File file = new File(imageDir, "camera_" + System.currentTimeMillis() + ".jpg");
        Uri uri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                file
        );
        return new CameraCaptureInfo(uri, file.getAbsolutePath());
    }

    public String persistCapturedPhoto(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            return null;
        }
        return Uri.fromFile(file).toString();
    }

    public void deletePhoto(String filePathOrUri) {
        if (filePathOrUri == null || filePathOrUri.trim().isEmpty()) {
            return;
        }
        try {
            File directFile = new File(filePathOrUri);
            File target = directFile.exists() ? directFile : new File(Uri.parse(filePathOrUri).getPath());
            if (target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 把图片文件写入备份 ZIP 的 images/ 目录。源图缺失时容错跳过（不影响整体备份成功）；
     * 写入流本身的异常（磁盘满等）向上抛出，让导出方删半成品并返回 null。
     */
    public void addImageToZip(ZipOutputStream zos, String fileUriOrPath) throws Exception {
        File source = resolveImageFile(fileUriOrPath);
        if (source == null || !source.exists()) {
            return;
        }
        zos.putNextEntry(new ZipEntry("images/" + source.getName()));
        try (FileInputStream fis = new FileInputStream(source)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                zos.write(buffer, 0, read);
            }
        }
        zos.closeEntry();
    }

    /** 把 file:// uri 或直接路径解析为 File，无法定位返回 null。 */
    public File resolveImageFile(String fileUriOrPath) {
        if (fileUriOrPath == null || fileUriOrPath.trim().isEmpty()) {
            return null;
        }
        File direct = new File(fileUriOrPath);
        if (direct.exists()) {
            return direct;
        }
        try {
            String path = Uri.parse(fileUriOrPath).getPath();
            if (path != null) {
                return new File(path);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 把二维码 Bitmap 保存到系统相册的 Pictures/Streak 目录。
     * API 29+ 走 MediaStore（免存储权限）；API 26-28 需调用方先拿到 WRITE_EXTERNAL_STORAGE。
     * 返回保存后的图片 Uri；失败返回 null。注意：应在后台线程调用，避免阻塞主线程。
     */
    public Uri saveQrToGallery(Bitmap bitmap, String displayName) {
        if (bitmap == null) {
            return null;
        }
        String fileName = sanitizeFileName(displayName) + "_" + System.currentTimeMillis() + ".png";
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Streak");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return null;
            }
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    resolver.delete(uri, null, null);
                    return null;
                }
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception e) {
                resolver.delete(uri, null, null);
                return null;
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri;
        } else {
            // API 26-28：写入公共 Pictures/Streak 目录，再插入 MediaStore 索引让相册可见
            File picturesDir = new File(
                    Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_PICTURES), "Streak");
            //noinspection ResultOfMethodCallIgnored
            picturesDir.mkdirs();
            File target = new File(picturesDir, fileName);
            try (FileOutputStream out = new FileOutputStream(target)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception e) {
                return null;
            }
            values.put(MediaStore.Images.Media.DATA, target.getAbsolutePath());
            return resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        }
    }

    /**
     * 把 Bitmap 写入 cache/shares 目录并返回可对外分享的 FileProvider content:// uri。
     * 用于成就战报等临时图片分享，失败返回 null。应在后台线程调用。
     */
    public Uri cacheBitmapForShare(Bitmap bitmap, String baseName) {
        if (bitmap == null) {
            return null;
        }
        try {
            File shareDir = new File(context.getCacheDir(), "shares");
            //noinspection ResultOfMethodCallIgnored
            shareDir.mkdirs();
            File target = new File(shareDir,
                    sanitizeFileName(baseName) + "_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream out = new FileOutputStream(target)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", target);
        } catch (Exception e) {
            return null;
        }
    }

    public String sanitizeFileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "streak_qr";
        }
        // 去掉文件名里的非法字符，避免拼路径出错
        String cleaned = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return cleaned.isEmpty() ? "streak_qr" : cleaned;
    }
}
