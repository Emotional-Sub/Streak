package com.streak.app.model;

public class UserAccount {
    private String username;
    // 旧版本以明文存储密码；新版本改用 passwordHash + salt。
    // 保留 password 字段仅用于读取旧数据并在首次登录时迁移。
    private String password;
    private String passwordHash;
    private String salt;

    public UserAccount() {
    }

    public UserAccount(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
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
     * 旧明文账号（只有 password、没有 hash）需要在首次登录时迁移。
     */
    public boolean isLegacyPlaintext() {
        return (passwordHash == null || passwordHash.isEmpty())
                && password != null && !password.isEmpty();
    }
}
