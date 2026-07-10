package com.streak.app.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

/**
 * 生成一张「成就战报」分享卡片 Bitmap（纯 Canvas 绘制，不联网）。
 * 支持不同维度（总成就 / 近 7 天 / 近 30 天），通过 {@link ReportData} 承载展示内容。
 * 可保存到相册或系统分享。
 */
public final class ShareCardGenerator {

    private static final int W = 1080;
    private static final int H = 1350; // 4:5，适配社交分享

    private ShareCardGenerator() {
    }

    /** 战报维度。 */
    public enum Scope {
        TOTAL("总成就"),
        LAST_7("近 7 天"),
        LAST_30("近 30 天");

        public final String label;

        Scope(String label) {
            this.label = label;
        }
    }

    /**
     * 战报展示数据：不同维度填充不同含义，卡片按此绘制，与统计口径解耦。
     * mainValue/mainLabel 是主数字；metricA/metricB 是两个次要指标。
     */
    public static class ReportData {
        public String displayName;
        public Scope scope;
        public String subtitle;      // 副标题（如「近 7 天回顾」）
        public int mainValue;
        public String mainLabel;
        public int metricAValue;
        public String metricALabel;
        public int metricBValue;
        public String metricBLabel;
        public String dateText;
    }

    /**
     * 按维度从习惯列表组装战报数据。badgeCount 仅「总成就」维度使用，由调用方传入
     * （避免本类依赖 BadgeUtils）。
     */
    public static ReportData buildData(Scope scope, String name,
                                       java.util.List<com.streak.app.model.HabitItem> habits,
                                       int badgeCount, String dateText) {
        ReportData d = new ReportData();
        d.displayName = name;
        d.scope = scope;
        d.dateText = dateText;
        switch (scope) {
            case LAST_7:
                d.subtitle = "近 7 天回顾";
                d.mainValue = HabitUtils.checkInsInLastDays(habits, 7);
                d.mainLabel = "近 7 天打卡（次）";
                d.metricAValue = HabitUtils.activeDaysInLastDays(habits, 7);
                d.metricALabel = "坚持天数";
                d.metricBValue = HabitUtils.bestCurrentStreak(habits);
                d.metricBLabel = "当前连续";
                break;
            case LAST_30:
                d.subtitle = "近 30 天回顾";
                d.mainValue = HabitUtils.checkInsInLastDays(habits, 30);
                d.mainLabel = "近 30 天打卡（次）";
                d.metricAValue = HabitUtils.activeDaysInLastDays(habits, 30);
                d.metricALabel = "坚持天数";
                d.metricBValue = HabitUtils.bestCurrentStreak(habits);
                d.metricBLabel = "当前连续";
                break;
            case TOTAL:
            default:
                d.subtitle = "总成就";
                d.mainValue = HabitUtils.bestLongestStreak(habits);
                d.mainLabel = "最长连续打卡（天）";
                d.metricAValue = HabitUtils.totalCheckIns(habits);
                d.metricALabel = "累计打卡";
                d.metricBValue = badgeCount;
                d.metricBLabel = "解锁勋章";
                break;
        }
        return d;
    }

    public static Bitmap generate(ReportData data) {
        if (data == null) {
            return null;
        }
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

        // 昵称 + 维度副标题
        Paint soft = new Paint(Paint.ANTI_ALIAS_FLAG);
        soft.setColor(0xCCFFFFFF);
        soft.setTextAlign(Paint.Align.CENTER);
        soft.setTextSize(40f);
        String name = data.displayName == null || data.displayName.trim().isEmpty()
                ? "我" : data.displayName.trim();
        String subtitle = data.subtitle == null || data.subtitle.isEmpty()
                ? name + " 的打卡战报" : name + " · " + data.subtitle;
        canvas.drawText(subtitle, W / 2f, 230, soft);

        // 主数字
        white.setTextSize(340f);
        white.setFakeBoldText(true);
        canvas.drawText(String.valueOf(data.mainValue), W / 2f, 640, white);
        soft.setTextSize(48f);
        canvas.drawText(data.mainLabel == null ? "" : data.mainLabel, W / 2f, 720, soft);

        // 分隔线
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(0x33FFFFFF);
        line.setStrokeWidth(2f);
        canvas.drawLine(140, 820, W - 140, 820, line);

        // 两个次要指标
        drawMetric(canvas, W / 2f - 240, 960, String.valueOf(data.metricAValue),
                data.metricALabel, white, soft);
        drawMetric(canvas, W / 2f + 240, 960, String.valueOf(data.metricBValue),
                data.metricBLabel, white, soft);

        // 底部：日期 + slogan
        soft.setTextSize(38f);
        soft.setColor(0xCCFFFFFF);
        canvas.drawText(data.dateText == null ? "" : data.dateText, W / 2f, 1220, soft);
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
        canvas.drawText(label == null ? "" : label, cx, baseY + 60, soft);
    }
}
