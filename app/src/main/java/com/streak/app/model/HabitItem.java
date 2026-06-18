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

    public HabitItem() {
        this.tags = new ArrayList<>();
        this.completedDates = new ArrayList<>();
        this.category = "学习";
        this.reminderTime = "20:00";
        this.reminderEnabled = true;
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
        return reminderTime;
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
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags == null ? new ArrayList<>() : tags;
    }

    public List<String> getCompletedDates() {
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
}
