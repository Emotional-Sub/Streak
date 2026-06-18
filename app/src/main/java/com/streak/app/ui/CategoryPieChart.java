package com.streak.app.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量分类分布饼图（甜甜圈样式），带扫掠进入动画。
 * 不依赖第三方图表库，纯 Canvas 绘制。
 */
public class CategoryPieChart extends View {

    public static class Slice {
        public final String label;
        public final float value;
        public final int color;

        public Slice(String label, float value, int color) {
            this.label = label;
            this.value = value;
            this.color = color;
        }
    }

    private final List<Slice> slices = new ArrayList<>();
    private final Paint slicePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint holePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();
    private float total = 0f;
    private float sweepProgress = 0f;
    private String centerText = "";
    private ValueAnimator animator;

    public CategoryPieChart(Context context) {
        super(context);
        init();
    }

    public CategoryPieChart(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CategoryPieChart(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        slicePaint.setStyle(Paint.Style.FILL);
        holePaint.setColor(Color.WHITE);
        holePaint.setStyle(Paint.Style.FILL);
        centerTextPaint.setColor(0xFF151827);
        centerTextPaint.setTextAlign(Paint.Align.CENTER);
        centerTextPaint.setFakeBoldText(true);
    }

    public void setData(List<Slice> data, String centerText) {
        slices.clear();
        total = 0f;
        if (data != null) {
            for (Slice slice : data) {
                if (slice.value > 0) {
                    slices.add(slice);
                    total += slice.value;
                }
            }
        }
        this.centerText = centerText == null ? "" : centerText;
        startSweepAnimation();
    }

    public void replay() {
        if (!slices.isEmpty() && total > 0f) {
            startSweepAnimation();
        }
    }

    private void startSweepAnimation() {
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
        sweepProgress = 0f;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(700);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            sweepProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (slices.isEmpty() || total <= 0f) {
            return;
        }

        int width = getWidth();
        int height = getHeight();
        float size = Math.min(width, height);
        float cx = width / 2f;
        float cy = height / 2f;
        float radius = size / 2f * 0.92f;

        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);

        float startAngle = -90f;
        for (Slice slice : slices) {
            float fullSweep = slice.value / total * 360f;
            float animatedSweep = fullSweep * sweepProgress;
            slicePaint.setColor(slice.color);
            canvas.drawArc(arcBounds, startAngle, animatedSweep, true, slicePaint);
            startAngle += fullSweep;
        }

        // 中心挖空形成甜甜圈
        float holeRadius = radius * 0.58f;
        canvas.drawCircle(cx, cy, holeRadius, holePaint);

        if (!centerText.isEmpty()) {
            centerTextPaint.setTextSize(holeRadius * 0.5f);
            float textY = cy - (centerTextPaint.descent() + centerTextPaint.ascent()) / 2f;
            canvas.drawText(centerText, cx, textY, centerTextPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
        }
        super.onDetachedFromWindow();
    }
}
