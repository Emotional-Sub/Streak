package com.streak.app.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    // 每个打卡日期对应的可选备注/心情，key=yyyy-MM-dd。老备份缺此字段时 Gson 置 null，按空处理。
    private Map<String, String> dateNotes;
    // 目标周期：0 表示「每天」，N(>0) 表示「每周 N 次」。老备份缺此字段默认 0（每天），行为不变。
    private int weeklyTarget;

    public HabitItem() {
        this.tags = new ArrayList<>();
        this.completedDates = new ArrayList<>();
        this.dateNotes = new HashMap<>();
        this.category = "学习";
        this.reminderTime = "20:00";
        this.reminderEnabled = true;
        this.weeklyTarget = 0;
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

    public Map<String, String> getDateNotes() {
        if (dateNotes == null) {
            dateNotes = new HashMap<>();
        }
        return dateNotes;
    }

    public void setDateNotes(Map<String, String> dateNotes) {
        this.dateNotes = dateNotes == null ? new HashMap<>() : dateNotes;
    }

    /** 取某天的打卡备注；无则返回空串。 */
    public String getNote(String date) {
        String note = getDateNotes().get(date);
        return note == null ? "" : note;
    }

    /** 设置/清除某天备注：空串等同清除，避免残留空条目。 */
    public void setNote(String date, String note) {
        if (date == null) {
            return;
        }
        if (note == null || note.trim().isEmpty()) {
            getDateNotes().remove(date);
        } else {
            getDateNotes().put(date, note.trim());
        }
    }

    /** 目标周期：0=每天，N=每周 N 次。 */
    public int getWeeklyTarget() {
        return weeklyTarget;
    }

    public void setWeeklyTarget(int weeklyTarget) {
        this.weeklyTarget = Math.max(0, Math.min(weeklyTarget, 7));
    }

    /** 是否为「每周 N 次」型目标（否则为每天）。 */
    public boolean isWeeklyGoal() {
        return weeklyTarget > 0;
    }
}
