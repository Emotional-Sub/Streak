package com.streak.app.model;

import java.util.List;

public class HabitBackup {
    private String exportedAt;
    private List<HabitItem> habits;

    public HabitBackup(String exportedAt, List<HabitItem> habits) {
        this.exportedAt = exportedAt;
        this.habits = habits;
    }

    public String getExportedAt() {
        return exportedAt;
    }

    public List<HabitItem> getHabits() {
        return habits;
    }
}
