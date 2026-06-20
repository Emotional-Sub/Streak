package com.streak.app.ui;

import android.os.Bundle;

import androidx.annotation.Nullable;

import com.journeyapps.barcodescanner.CaptureActivity;

/**
 * 竖屏锁定的扫码 Activity。
 * zxing-embedded 默认的 CaptureActivity 会跟随传感器旋转，这里固定竖屏。
 * 竖屏锁定在 AndroidManifest 中通过 android:screenOrientation="portrait" 声明。
 */
public class PortraitCaptureActivity extends CaptureActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
}
