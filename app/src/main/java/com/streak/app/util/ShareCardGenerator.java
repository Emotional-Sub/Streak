package com.streak.app.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

/**
 * 生成一张「成就战报」分享卡片 Bitmap（纯 Canvas 绘制，不联网）。
 * 展示：用户昵称、坚持天数、累计打卡、勋章数、生成日期。可保存到相册或系统分享。
 */
public final class ShareCardGenerator {

    private static final int W = 1080;
    private static final int H = 1350; // 4:5，适配社交分享

    private ShareCardGenerator() {
    }

    public static Bitmap generate(String displayName, int longestStreak,
                                  int totalCheckIns, int badgeCount, String dateText) {
        Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // 背景：紫色渐变
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, W, H,
                0xFF5B5FEF, 0xFF8A63D2, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, W, H, bg);

        Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
        white.setColor(0xFFFFFFFF);
        white.setTextAlign(Paint.Align.CENTER);

        // 顶部品牌
        white.setTextSize(52f);
        white.setFakeBoldText(true);
        canvas.drawText("Streak · 我的坚持", W / 2f, 150, white);

        // 昵称问候
        Paint soft = new Paint(Paint.ANTI_ALIAS_FLAG);
        soft.setColor(0xCCFFFFFF);
        soft.setTextAlign(Paint.Align.CENTER);
        soft.setTextSize(40f);
        String name = displayName == null || displayName.trim().isEmpty() ? "我" : displayName.trim();
        canvas.drawText(name + " 的打卡战报", W / 2f, 230, soft);

        // 主数字：最长连续天数
        white.setTextSize(340f);
        white.setFakeBoldText(true);
        canvas.drawText(String.valueOf(longestStreak), W / 2f, 640, white);
        soft.setTextSize(48f);
        canvas.drawText("最长连续打卡（天）", W / 2f, 720, soft);

        // 分隔线
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(0x33FFFFFF);
        line.setStrokeWidth(2f);
        canvas.drawLine(140, 820, W - 140, 820, line);

        // 两个次要指标：累计打卡 / 勋章数
        drawMetric(canvas, W / 2f - 240, 960, String.valueOf(totalCheckIns), "累计打卡", white, soft);
        drawMetric(canvas, W / 2f + 240, 960, String.valueOf(badgeCount), "解锁勋章", white, soft);

        // 底部：日期 + slogan
        soft.setTextSize(38f);
        canvas.drawText(dateText == null ? "" : dateText, W / 2f, 1220, soft);
        soft.setTextSize(34f);
        soft.setColor(0x99FFFFFF);
        canvas.drawText("每天一点点，攒起来就是了不起", W / 2f, 1280, soft);

        return bitmap;
    }

    private static void drawMetric(Canvas canvas, float cx, float baseY,
                                   String value, String label, Paint white, Paint soft) {
        white.setTextSize(110f);
        white.setFakeBoldText(true);
        white.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(value, cx, baseY, white);
        soft.setTextSize(40f);
        soft.setColor(0xCCFFFFFF);
        canvas.drawText(label, cx, baseY + 60, soft);
    }
}
