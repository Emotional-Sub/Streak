package com.streak.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 打卡热力图（GitHub 贡献墙样式）：近若干周 × 7 天的圆角色块，
 * 每格颜色深浅表示当天全部习惯的去重打卡次数。纯 Canvas 绘制，不依赖第三方库。
 */
public class StreakHeatmapView extends View {

    // 展示的周数：18 周约等于最近 4 个月，宽度适中
    private static final int WEEKS = 18;
    private static final int DAYS_PER_WEEK = 7;

    private final Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cellRect = new RectF();

    // 每个日期(yyyy-MM-dd)对应的打卡计数
    private final Map<String, Integer> counts = new HashMap<>();
    private int maxCount = 0;

    // 5 档配色：从主题色资源读取，随浅色/深色模式自动切换（heat_0 最浅、heat_4 最深）
    private final int[] levelColors = new int[5];

    public StreakHeatmapView(Context context) {
        super(context);
        loadColors();
    }

    public StreakHeatmapView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        loadColors();
    }

    public StreakHeatmapView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        loadColors();
    }

    private void loadColors() {
        levelColors[0] = androidx.core.content.ContextCompat.getColor(getContext(), com.streak.app.R.color.heat_0);
        levelColors[1] = androidx.core.content.ContextCompat.getColor(getContext(), com.streak.app.R.color.heat_1);
        levelColors[2] = androidx.core.content.ContextCompat.getColor(getContext(), com.streak.app.R.color.heat_2);
        levelColors[3] = androidx.core.content.ContextCompat.getColor(getContext(), com.streak.app.R.color.heat_3);
        levelColors[4] = androidx.core.content.ContextCompat.getColor(getContext(), com.streak.app.R.color.heat_4);
    }

    /** 传入「日期 -> 打卡次数」的映射，内部只读取近 WEEKS 周窗口。 */
    public void setData(Map<String, Integer> dateCounts) {
        counts.clear();
        if (dateCounts != null) {
            counts.putAll(dateCounts);
        }
        // maxCount 在 onDraw 里按实际绘制的可见窗口计算，
        // 不能用全量数据的最大值：窗口外的历史峰值会拉高归一化基准，
        // 让近期所有色块偏浅，误导对最近强度的判断。
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 宽度撑满父容器，高度按格子尺寸推导，保证正方形格子 + 间距
        int width = MeasureSpec.getSize(widthMeasureSpec);
        float cell = cellSizeFor(width);
        float gap = cell * 0.18f;
        int height = (int) Math.ceil(DAYS_PER_WEEK * cell + (DAYS_PER_WEEK - 1) * gap);
        setMeasuredDimension(width, height);
    }

    private float cellSizeFor(int width) {
        // WEEKS 列，列间距为格子的 0.18 倍
        float ratio = 0.18f;
        return width / (WEEKS + (WEEKS - 1) * ratio);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        if (width <= 0) {
            return;
        }
        float cell = cellSizeFor(width);
        float gap = cell * 0.18f;
        float radius = cell * 0.22f;

        // 以「今天所在周的周日」为最后一列的基准，往前铺 WEEKS 列
        LocalDate today = LocalDate.now();
        // 计算今天在其所在周的列内行号（周日=0 ... 周六=6）
        int todayRow = today.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : today.getDayOfWeek().getValue();
        // 最后一列第 0 行对应的日期（本周周日）
        LocalDate lastColSunday = today.minusDays(todayRow);

        // 归一化基准只统计将要绘制的可见窗口内、且不晚于今天的计数，
        // 保证颜色深浅反映近期强度而非久远的历史峰值
        maxCount = 0;
        for (int col = 0; col < WEEKS; col++) {
            LocalDate colSunday = lastColSunday.minusWeeks(WEEKS - 1 - col);
            for (int row = 0; row < DAYS_PER_WEEK; row++) {
                LocalDate date = colSunday.plusDays(row);
                if (date.isAfter(today)) {
                    continue;
                }
                maxCount = Math.max(maxCount, count(date));
            }
        }

        for (int col = 0; col < WEEKS; col++) {
            // 从最早的一列开始画：最早列 = 最后一列往前推 (WEEKS-1-col) 周
            LocalDate colSunday = lastColSunday.minusWeeks(WEEKS - 1 - col);
            for (int row = 0; row < DAYS_PER_WEEK; row++) {
                LocalDate date = colSunday.plusDays(row);
                if (date.isAfter(today)) {
                    continue; // 未来日期不画
                }
                float left = col * (cell + gap);
                float top = row * (cell + gap);
                cellRect.set(left, top, left + cell, top + cell);
                cellPaint.setColor(colorForCount(count(date)));
                canvas.drawRoundRect(cellRect, radius, radius, cellPaint);
            }
        }
    }

    private int count(LocalDate date) {
        Integer v = counts.get(date.toString());
        return v == null ? 0 : v;
    }

    private int colorForCount(int c) {
        if (c <= 0) {
            return levelColors[0];
        }
        if (maxCount <= 1) {
            return levelColors[levelColors.length - 1];
        }
        // 把 1..maxCount 映射到 1..4 档
        int level = 1 + (int) Math.floor((c - 1) / (float) maxCount * (levelColors.length - 2));
        if (level < 1) {
            level = 1;
        }
        if (level > levelColors.length - 1) {
            level = levelColors.length - 1;
        }
        return levelColors[level];
    }

    // 预留：未来若接主题色可覆盖 levelColors；当前用固定绿阶
    @SuppressWarnings("unused")
    private static int fade(int color, float alpha) {
        int a = Math.round(255 * alpha);
        return (a << 24) | (color & 0x00FFFFFF);
    }
}
