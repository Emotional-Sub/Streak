package com.streak.app.model;

/**
 * 勋章。是否点亮由 BadgeUtils 根据真实打卡数据评估。
 */
public class Badge {
    private final String key;
    private final String title;
    private final String description;
    private final String emoji;
    private final boolean unlocked;

    public Badge(String key, String title, String description, String emoji, boolean unlocked) {
        this.key = key;
        this.title = title;
        this.description = description;
        this.emoji = emoji;
        this.unlocked = unlocked;
    }

    public String getKey() {
        return key;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getEmoji() {
        return emoji;
    }

    public boolean isUnlocked() {
        return unlocked;
    }
}
