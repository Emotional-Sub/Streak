package com.streak.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;

import com.journeyapps.barcodescanner.ViewfinderView;

/**
 * 取景视图：只画半透明遮罩 + 圆角白框，去掉默认的激光横线。
 */
public class FramingViewfinderView extends ViewfinderView {

    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public FramingViewfinderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        cornerPaint.setColor(Color.WHITE);
        cornerPaint.setStrokeWidth(dp(3));
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    public void onDraw(Canvas canvas) {
        refreshSizes();
        if (framingRect == null || previewSize == null) {
            return;
        }

        Rect frame = framingRect;
        int width = canvas.getWidth();
        int height = canvas.getHeight();

        // 四周半透明遮罩
        paint.setColor(0x99000000);
        canvas.drawRect(0, 0, width, frame.top, paint);
        canvas.drawRect(0, frame.top, frame.left, frame.bottom + 1, paint);
        canvas.drawRect(frame.right + 1, frame.top, width, frame.bottom + 1, paint);
        canvas.drawRect(0, frame.bottom + 1, width, height, paint);

        // 四角直角标记
        int len = Math.min(frame.width(), frame.height()) / 6;
        // 左上
        canvas.drawLine(frame.left, frame.top, frame.left + len, frame.top, cornerPaint);
        canvas.drawLine(frame.left, frame.top, frame.left, frame.top + len, cornerPaint);
        // 右上
        canvas.drawLine(frame.right, frame.top, frame.right - len, frame.top, cornerPaint);
        canvas.drawLine(frame.right, frame.top, frame.right, frame.top + len, cornerPaint);
        // 左下
        canvas.drawLine(frame.left, frame.bottom, frame.left + len, frame.bottom, cornerPaint);
        canvas.drawLine(frame.left, frame.bottom, frame.left, frame.bottom - len, cornerPaint);
        // 右下
        canvas.drawLine(frame.right, frame.bottom, frame.right - len, frame.bottom, cornerPaint);
        canvas.drawLine(frame.right, frame.bottom, frame.right, frame.bottom - len, cornerPaint);
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
