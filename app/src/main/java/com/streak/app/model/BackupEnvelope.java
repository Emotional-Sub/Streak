package com.streak.app.model;

import java.util.List;

/**
 * 备份的顶层版本化信封（v4 起）。
 *
 * <p><b>为什么要显式版本号。</b>早期导入靠「某个 JSON 文件是否存在」来推断备份形态
 * （有 check_in_records.json 就当新版、否则回退 habits.json 的 completedDates/notes），
 * 这种「按文件在不在猜版本」既脆弱又难演进——新增一种结构就得再加一层文件探测。
 * 改为在信封里写死 {@code schemaVersion}，导入时按版本号分派兼容转换，语义明确、可扩展。</p>
 *
 * <p><b>兼容策略。</b>导出同时写两份：新版写本信封 {@code backup.json}（schemaVersion=4，
 * 含全保真的 checkInRecords：心情/耗时/照片），并保留旧的 {@code habits.json}
 * （completedDates/notes 视图）供旧版本 App 导入。导入优先读 {@code backup.json}，
 * 缺失时回退到旧的按文件推断逻辑（老备份没有信封）。</p>
 */
public class BackupEnvelope {

    /** 当前备份结构版本，与数据库 schema 版本对齐（v4：打卡记录独立表 + 心情/耗时/照片）。 */
    public static final int CURRENT_SCHEMA_VERSION = 4;

    private int schemaVersion;
    private String exportedAt;
    private List<HabitItem> habits;
    private List<CheckInRecord> checkInRecords;
    private List<UserAccount> accounts;

    public BackupEnvelope() {
    }

    public BackupEnvelope(int schemaVersion, String exportedAt, List<HabitItem> habits,
                          List<CheckInRecord> checkInRecords, List<UserAccount> accounts) {
        this.schemaVersion = schemaVersion;
        this.exportedAt = exportedAt;
        this.habits = habits;
        this.checkInRecords = checkInRecords;
        this.accounts = accounts;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getExportedAt() {
        return exportedAt;
    }

    public void setExportedAt(String exportedAt) {
        this.exportedAt = exportedAt;
    }

    public List<HabitItem> getHabits() {
        return habits;
    }

    public void setHabits(List<HabitItem> habits) {
        this.habits = habits;
    }

    public List<CheckInRecord> getCheckInRecords() {
        return checkInRecords;
    }

    public void setCheckInRecords(List<CheckInRecord> checkInRecords) {
        this.checkInRecords = checkInRecords;
    }

    public List<UserAccount> getAccounts() {
        return accounts;
    }

    public void setAccounts(List<UserAccount> accounts) {
        this.accounts = accounts;
    }
}
