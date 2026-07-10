package com.streak.app.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.widget.ImageView;

import java.io.InputStream;

/**
 * 图片加载工具：对 file:// 本地图片做降采样解码，避免把相机原图（动辄数千万像素）
 * 整张读进内存，导致主线程卡顿甚至 OOM（OOM 时图片就直接不显示）。
 * 解码控制在目标显示尺寸量级，足够清晰且大幅降低内存占用。
 */
public final class ImageLoader {

    private ImageLoader() {
    }

    /**
     * 将 uri 指向的图片降采样后设置到 ImageView。
     * 解析失败时回退为 setImageURI，保证行为不劣于原实现。
     *
     * @param targetSizePx 目标边长（像素），按此量级选择采样率
     */
    public static void load(ImageView view, String uriString, int targetSizePx) {
        if (view == null) {
            return;
        }
        if (uriString == null || uriString.trim().isEmpty()) {
            view.setImageDrawable(null);
            return;
        }
        Uri uri = Uri.parse(uriString);
        try {
            Bitmap bitmap = decodeSampled(view, uri, targetSizePx);
            if (bitmap != null) {
                view.setImageBitmap(bitmap);
                return;
            }
        } catch (Throwable ignored) {
            // 解码异常（含 OOM）时回退
        }
        try {
            view.setImageURI(uri);
        } catch (Throwable ignored) {
            view.setImageDrawable(null);
        }
    }

    private static Bitmap decodeSampled(ImageView view, Uri uri, int targetSizePx) throws Exception {
        // 第一遍：只读尺寸，不分配像素内存
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = view.getContext().getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int target = targetSizePx > 0 ? targetSizePx : 512;

        // 第二遍：按采样率真正解码
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, target);
        try (InputStream in = view.getContext().getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            return BitmapFactory.decodeStream(in, null, opts);
        }
    }

    private static int calculateInSampleSize(int width, int height, int target) {
        int sampleSize = 1;
        int halfW = width / 2;
        int halfH = height / 2;
        // 用 || 而非 &&：只要任一维度仍超目标就继续降采样。
        // 否则极端宽高比图片（如 8000×400 全景/长截图）会因短边早早低于 target
        // 而完全跳过降采样，最终按原始像素解码导致 OOM。
        while (halfW / sampleSize >= target || halfH / sampleSize >= target) {
            sampleSize *= 2;
        }
        return sampleSize;
    }
}
