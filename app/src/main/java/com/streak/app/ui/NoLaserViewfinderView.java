package com.streak.app.ui;

import android.content.Context;
import android.util.AttributeSet;

import com.journeyapps.barcodescanner.ViewfinderView;

/**
 * 去掉激光扫描线和四角动画点的取景框。
 * zxing 默认 ViewfinderView 会在中间画一条红色激光横线，这里只保留半透明遮罩，
 * onDraw 不再绘制激光线与结果点，界面更干净（参考主流大厂扫码 UI）。
 */
public class NoLaserViewfinderView extends ViewfinderView {
    public NoLaserViewfinderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // 激光线靠 laserVisibility 控制，关闭后只保留半透明遮罩。
        setLaserVisibility(false);
    }
}
