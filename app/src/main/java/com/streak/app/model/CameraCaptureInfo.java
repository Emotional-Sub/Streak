package com.streak.app.model;

import android.net.Uri;

public class CameraCaptureInfo {
    private final Uri uri;
    private final String filePath;

    public CameraCaptureInfo(Uri uri, String filePath) {
        this.uri = uri;
        this.filePath = filePath;
    }

    public Uri getUri() {
        return uri;
    }

    public String getFilePath() {
        return filePath;
    }
}
