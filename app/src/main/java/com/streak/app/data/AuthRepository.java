package com.streak.app.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 会话态仓库（Phase B 从 {@code AppRepository} 拆出）：登录态、记住的用户名、主题偏好。
 *
 * <p><b>职责边界。</b>只读写 SharedPreferences 里的会话态——当前登录用户名、是否记住用户名、
 * 主题模式。账号数据与凭据属 {@link UserRepository}，本类不碰 accounts 表。</p>
 *
 * <p><b>安全整改沿革。</b>历史版本曾在 SharedPreferences 里存明文密码，现已停存：只在勾选
 * 「记住用户名」时保存用户名，绝不再持久化任何密码。旧明文密码由
 * {@code AppRepository.purgeLegacyPlaintextPassword()} 启动时清除。</p>
 */
public class AuthRepository {

    private static final String PREFS_NAME = "java_streak_prefs";
    private static final String KEY_SAVED_USERNAME = "saved_username";
    private static final String KEY_LEGACY_SAVED_PASSWORD = "saved_password";
    private static final String KEY_REMEMBER_PASSWORD = "remember_password";
    private static final String KEY_CURRENT_USER = "current_user";
    private static final String KEY_THEME_MODE = "theme_mode";

    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    private final SharedPreferences preferences;

    public AuthRepository(Context context) {
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 清除历史版本明文存储在 SharedPreferences 里的密码（安全整改）。
     * 现在只记住用户名，不再回填/持久化任何密码。
     */
    public void purgeLegacyPlaintextPassword() {
        if (preferences.contains(KEY_LEGACY_SAVED_PASSWORD)) {
            preferences.edit().remove(KEY_LEGACY_SAVED_PASSWORD).apply();
        }
    }

    /**
     * 保存登录态。安全整改：只在「记住用户名」勾选时保存用户名，绝不再持久化密码。
     * 保留 password 形参是为了兼容调用方签名，但不写入任何存储。
     */
    public void saveLoginState(String username, String password, boolean rememberPassword, String currentUser) {
        preferences.edit()
                .putBoolean(KEY_REMEMBER_PASSWORD, rememberPassword)
                .putString(KEY_CURRENT_USER, currentUser)
                .putString(KEY_SAVED_USERNAME, rememberPassword ? username : "")
                .apply();
    }

    /** 清空当前登录用户名（退出登录）。提醒的取消由门面在调用前编排。 */
    public void clearCurrentUser() {
        preferences.edit().putString(KEY_CURRENT_USER, "").apply();
    }

    /** 删号后清空记住的用户名。 */
    public void clearSavedUsername() {
        preferences.edit().putString(KEY_SAVED_USERNAME, "").apply();
    }

    public String getSavedUsername() {
        return preferences.getString(KEY_SAVED_USERNAME, "");
    }

    /** 是否记住用户名（复选框语义已从「记住密码」收敛为「记住用户名」）。 */
    public boolean isRememberPassword() {
        return preferences.getBoolean(KEY_REMEMBER_PASSWORD, true);
    }

    public String getCurrentUser() {
        return preferences.getString(KEY_CURRENT_USER, "");
    }

    /** 读取主题模式偏好，默认跟随系统。 */
    public int getThemeMode() {
        return preferences.getInt(KEY_THEME_MODE, THEME_SYSTEM);
    }

    public void setThemeMode(int mode) {
        preferences.edit().putInt(KEY_THEME_MODE, mode).apply();
    }

    /**
     * 账号改名后同步会话态：若改的是当前登录账号，更新 current_user；
     * 若改的是记住的用户名，更新 saved_username。由门面在 {@code UserRepository.updateAccount}
     * 返回成功后调用（账号数据与会话态分属两类，各自内聚）。
     */
    public void syncRenamedUser(String oldUsername, String newUsername) {
        SharedPreferences.Editor editor = preferences.edit();
        if (oldUsername.equals(getCurrentUser())) {
            editor.putString(KEY_CURRENT_USER, newUsername);
        }
        if (oldUsername.equals(getSavedUsername())) {
            editor.putString(KEY_SAVED_USERNAME, newUsername);
        }
        editor.apply();
    }
}
