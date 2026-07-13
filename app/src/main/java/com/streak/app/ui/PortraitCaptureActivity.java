package com.streak.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.zxing.client.android.Intents;
import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.streak.app.R;
import com.streak.app.util.QrDecoder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 竖屏锁定的扫码 Activity，并在扫码界面内置「从相册选码」入口（仿主流扫一扫）。
 * 竖屏锁定在 AndroidManifest 中通过 android:screenOrientation="portrait" 声明。
 *
 * 注意：父类 CaptureActivity 继承自 android.app.Activity（非 AndroidX ComponentActivity），
 * 因此这里用经典的 startActivityForResult/onActivityResult，而非 registerForActivityResult。
 * 相册图片解出二维码后，按 zxing 的结果格式（Intents.Scan.RESULT）回填，
 * 使调用方（MainActivity 的 ScanContract 回调）能与相机扫码统一处理。
 */
public class PortraitCaptureActivity extends CaptureActivity {

    private static final int REQ_PICK_QR_IMAGE = 0x51;

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected DecoratedBarcodeView initializeContent() {
        setContentView(R.layout.activity_portrait_capture);
        findViewById(R.id.btnCaptureBack).setOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });
        findViewById(R.id.btnPickFromGallery).setOnClickListener(v -> pickImageFromGallery());
        return findViewById(R.id.zxing_barcode_scanner);
    }

    private void pickImageFromGallery() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(
                    Intent.createChooser(intent, getString(R.string.qr_image_chooser_title)), REQ_PICK_QR_IMAGE);
        } catch (Exception e) {
            Toast.makeText(this, R.string.toast_no_gallery_app, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_QR_IMAGE || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        Toast.makeText(this, R.string.toast_recognizing_qr, Toast.LENGTH_SHORT).show();
        backgroundExecutor.execute(() -> {
            String raw = QrDecoder.decodeFromUri(this, uri);
            mainHandler.post(() -> onImageDecoded(raw));
        });
    }

    private void onImageDecoded(@Nullable String raw) {
        if (isFinishing()) {
            return;
        }
        if (raw == null) {
            // 没扫到就留在相机界面，让用户继续扫或重选
            Toast.makeText(this, R.string.toast_qr_not_recognized_pick_clearer, Toast.LENGTH_SHORT).show();
            return;
        }
        // 按 zxing 结果格式回填，使调用方与相机扫码走同一条解析路径
        Intent result = new Intent(Intents.Scan.ACTION);
        result.putExtra(Intents.Scan.RESULT, raw);
        setResult(Activity.RESULT_OK, result);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        backgroundExecutor.shutdownNow();
    }
}
