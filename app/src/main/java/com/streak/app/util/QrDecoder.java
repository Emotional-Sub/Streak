package com.streak.app.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * 从相册图片里解码二维码（纯离线）。对大图先降采样再解码，避免把相机原图整张读进内存导致 OOM。
 * 先用 HybridBinarizer 试一次，失败再用 GlobalHistogramBinarizer + TRY_HARDER 兜底，
 * 提升对拍照/截图二维码的识别率。应在后台线程调用。
 */
public final class QrDecoder {

    // 解码用的目标边长：太小细节丢失，太大浪费内存，1024 对常见二维码足够
    private static final int TARGET_SIZE = 1024;

    private QrDecoder() {
    }

    /** 解码失败（图片读不到或没有二维码）返回 null。 */
    public static String decodeFromUri(Context context, Uri uri) {
        if (context == null || uri == null) {
            return null;
        }
        Bitmap bitmap = decodeSampledBitmap(context, uri);
        if (bitmap == null) {
            return null;
        }
        try {
            return decodeBitmap(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    private static String decodeBitmap(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        LuminanceSource source = new RGBLuminanceSource(width, height, pixels);

        QRCodeReader reader = new QRCodeReader();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");

        // 第一遍：HybridBinarizer（适合多数场景）
        String result = tryDecode(reader, new BinaryBitmap(new HybridBinarizer(source)), hints);
        if (result != null) {
            return result;
        }
        // 第二遍：GlobalHistogramBinarizer + TRY_HARDER（对低对比度/拍照图更稳）
        reader.reset();
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        return tryDecode(reader, new BinaryBitmap(new GlobalHistogramBinarizer(source)), hints);
    }

    private static String tryDecode(QRCodeReader reader, BinaryBitmap bitmap,
                                    Map<DecodeHintType, Object> hints) {
        try {
            Result result = reader.decode(bitmap, hints);
            return result == null ? null : result.getText();
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap decodeSampledBitmap(Context context, Uri uri) {
        try {
            // 第一遍：只读尺寸
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    return null;
                }
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            // 第二遍：按采样率真正解码
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, TARGET_SIZE);
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    return null;
                }
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Throwable e) {
            return null;
        }
    }

    private static int calculateInSampleSize(int width, int height, int target) {
        int sampleSize = 1;
        int halfW = width / 2;
        int halfH = height / 2;
        while (halfW / sampleSize >= target && halfH / sampleSize >= target) {
            sampleSize *= 2;
        }
        return sampleSize;
    }
}
