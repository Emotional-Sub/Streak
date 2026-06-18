package com.streak.app.model;

public class CalendarCell {
    private final boolean empty;
    private final int day;
    private final String date;
    private final boolean today;
    private final boolean completed;

    public CalendarCell(boolean empty, int day, String date, boolean today, boolean completed) {
        this.empty = empty;
        this.day = day;
        this.date = date;
        this.today = today;
        this.completed = completed;
    }

    public boolean isEmpty() {
        return empty;
    }

    public int getDay() {
        return day;
    }

    public String getDate() {
        return date;
    }

    public boolean isToday() {
        return today;
    }

    public boolean isCompleted() {
        return completed;
    }
}
