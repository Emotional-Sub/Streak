package com.streak.app.model;

import java.util.Arrays;
import java.util.List;

/**
 * 预置习惯模板，用于在新增习惯时快速填充常用内容。
 */
public class HabitTemplate {
    private final String title;
    private final String content;
    private final String category;
    private final String reminderTime;
    private final List<String> tags;

    public HabitTemplate(String title, String content, String category, String reminderTime, List<String> tags) {
        this.title = title;
        this.content = content;
        this.category = category;
        this.reminderTime = reminderTime;
        this.tags = tags;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getCategory() {
        return category;
    }

    public String getReminderTime() {
        return reminderTime;
    }

    public List<String> getTags() {
        return tags;
    }

    public String tagsText() {
        return String.join(", ", tags);
    }

    /**
     * 内置模板列表。
     */
    public static List<HabitTemplate> presets() {
        return Arrays.asList(
                new HabitTemplate("晨跑打卡", "坚持晨跑 30 分钟，结束后做 5 分钟拉伸。",
                        "运动", "06:30", Arrays.asList("晨练", "有氧")),
                new HabitTemplate("背英语单词", "复习 25 个英语单词，并拍照记录学习笔记。",
                        "学习", "21:00", Arrays.asList("英语", "单词")),
                new HabitTemplate("每日阅读", "阅读 20 页书籍，记录一句最有感触的话。",
                        "阅读", "22:00", Arrays.asList("读书")),
                new HabitTemplate("喝水提醒", "每天喝够 8 杯水，保持身体水分充足。",
                        "生活", "10:00", Arrays.asList("健康")),
                new HabitTemplate("冥想放松", "睡前冥想 10 分钟，放松身心，改善睡眠。",
                        "生活", "23:00", Arrays.asList("正念", "睡眠")),
                new HabitTemplate("整理工作清单", "下班前整理明日待办事项，理清优先级。",
                        "工作", "18:00", Arrays.asList("效率", "计划"))
        );
    }
}
