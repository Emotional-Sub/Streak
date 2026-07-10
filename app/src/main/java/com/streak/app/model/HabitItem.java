package com.streak.app.model;

import java.util.ArrayList;
import java.util.List;

public class HabitItem {
    private long id;
    private String title;
    private String content;
    private String reminderTime;
    private String createdAt;
    private String imageUri;
    private String category;
    private List<String> tags;
    private List<String> completedDates;
    private boolean reminderEnabled;
    // 目标周期：0=每天，1..6=每周 N 次
    private int weeklyTarget;
    // 每日打卡备注/心情：日期(yyyy-MM-dd) -> 文本
    private java.util.Map<String, String> notes;

    public HabitItem() {
        this.tags = new ArrayList<>();
        this.completedDates = new ArrayList<>();
        this.category = "学习";
        this.reminderTime = "20:00";
        this.reminderEnabled = true;
        this.weeklyTarget = 0;
        this.notes = new java.util.HashMap<>();
    }

    public HabitItem(long id, String title, String content, String reminderTime, String createdAt,
                     String imageUri, String category, List<String> tags,
                     List<String> completedDates, boolean reminderEnabled) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.reminderTime = reminderTime;
        this.createdAt = createdAt;
        this.imageUri = imageUri;
        this.category = category;
        this.tags = tags == null ? new ArrayList<>() : tags;
        this.completedDates = completedDates == null ? new ArrayList<>() : completedDates;
        this.reminderEnabled = reminderEnabled;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getReminderTime() {
        return reminderTime == null ? "20:00" : reminderTime;
    }

    public void setReminderTime(String reminderTime) {
        this.reminderTime = reminderTime;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getImageUri() {
        return imageUri;
    }

    public void setImageUri(String imageUri) {
        this.imageUri = imageUri;
    }

    public String getCategory() {
        return category == null ? "学习" : category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public List<String> getTags() {
        if (tags == null) {
            tags = new ArrayList<>();
        }
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags == null ? new ArrayList<>() : tags;
    }

    public List<String> getCompletedDates() {
        if (completedDates == null) {
            completedDates = new ArrayList<>();
        }
        return completedDates;
    }

    public void setCompletedDates(List<String> completedDates) {
        this.completedDates = completedDates == null ? new ArrayList<>() : completedDates;
    }

    public boolean isReminderEnabled() {
        return reminderEnabled;
    }

    public void setReminderEnabled(boolean reminderEnabled) {
        this.reminderEnabled = reminderEnabled;
    }

    public int getWeeklyTarget() {
        return weeklyTarget;
    }

    public void setWeeklyTarget(int weeklyTarget) {
        this.weeklyTarget = weeklyTarget;
    }

    /** true 表示按「每周 N 次」目标；false 表示「每天」。 */
    public boolean isWeeklyGoal() {
        return weeklyTarget > 0;
    }

    /** 取某天的打卡备注，无则返回空串（不返回 null，便于调用方直接判空/展示）。 */
    public String getNote(String date) {
        if (notes == null || date == null) {
            return "";
        }
        String note = notes.get(date);
        return note == null ? "" : note;
    }

    /**
     * 设置/清除某天的打卡备注。note 为空则移除该天记录，避免留下空条目。
     */
    public void setNote(String date, String note) {
        if (date == null) {
            return;
        }
        if (notes == null) {
            notes = new java.util.HashMap<>();
        }
        if (note == null || note.trim().isEmpty()) {
            notes.remove(date);
        } else {
            notes.put(date, note.trim());
        }
    }
}
