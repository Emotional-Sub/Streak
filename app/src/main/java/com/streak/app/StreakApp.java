package com.streak.app;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.streak.app.storage.AppRepository;

/**
 * 应用入口：启动时按用户持久化的主题偏好应用深色/浅色模式，
 * 保证冷启动即生效（不必等进入某个 Activity）。
 */
public class StreakApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        applyTheme(new AppRepository(this).getThemeMode());
    }

    /** 把 AppRepository 的主题常量映射到 AppCompatDelegate 的夜间模式并应用。 */
    public static void applyTheme(int themeMode) {
        int nightMode;
        switch (themeMode) {
            case AppRepository.THEME_LIGHT:
                nightMode = AppCompatDelegate.MODE_NIGHT_NO;
                break;
            case AppRepository.THEME_DARK:
                nightMode = AppCompatDelegate.MODE_NIGHT_YES;
                break;
            default:
                nightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                break;
        }
        AppCompatDelegate.setDefaultNightMode(nightMode);
    }
}
