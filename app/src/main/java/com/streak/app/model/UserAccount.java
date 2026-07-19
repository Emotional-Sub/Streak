package com.streak.app.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "accounts")
public class UserAccount {
    // 用户名天然唯一，直接作主键（Room 主键不可为 null）。
    @PrimaryKey
    @NonNull
    private String username = "";
    // 旧版本以明文存储密码；新版本改用 passwordHash + salt。
    // 保留 password 字段仅用于读取旧数据并在首次登录时迁移。
    private String password;
    private String passwordHash;
    private String salt;
    // 个人资料
    private String displayName;
    private String motto;
    private String avatarUri;

    public UserAccount() {
    }

    // Room 只能有一个非 @Ignore 构造器；这个便捷构造器仅供业务代码使用。
    @Ignore
    public UserAccount(String username, String password) {
        this.username = username == null ? "" : username;
        this.password = password;
    }

    @NonNull
    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username == null ? "" : username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getMotto() {
        return motto;
    }

    public void setMotto(String motto) {
        this.motto = motto;
    }

    public String getAvatarUri() {
        return avatarUri;
    }

    public void setAvatarUri(String avatarUri) {
        this.avatarUri = avatarUri;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getSalt() {
        return salt;
    }

    public void setSalt(String salt) {
        this.salt = salt;
    }

    /**
     * 旧明文账号（只有 password、没有 hash/salt）需要在首次登录时迁移。
     * 半对 hash/salt 或明文与新凭据混存都属于损坏凭据，不能降级为旧格式。
     */
    public boolean isLegacyPlaintext() {
        return (passwordHash == null || passwordHash.isEmpty())
                && (salt == null || salt.isEmpty())
                && password != null && !password.isEmpty();
    }
}
