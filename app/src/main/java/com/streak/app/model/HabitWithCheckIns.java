package com.streak.app.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 习惯 + 其打卡记录的只读组合视图。
 *
 * <p>替代此前挂在 {@link HabitItem} 上的去规范化派生字段（completedDates/notes @Ignore）：让统计、
 * 分析与 UI 消费端拿到某习惯的 per-habit 打卡数据，又不必给实体塞非持久化字段，也不必让纯函数层
 * 依赖 DAO/仓库。真相源仍是 {@link CheckInRecord} 表，本类只是把「一条习惯 + 它的记录列表」打包在一起。</p>
 *
 * <p><b>保真保留 records。</b>不只存日期集合，而是持有原始 {@link CheckInRecord} 列表——mood/duration/photo
 * 一并带上，供「编辑历史打卡」等后续功能直接消费，避免只留日期集合后再返工。日期集合/备注 Map 由记录
 * 惰性派生并缓存，供接收 {@code List<String>}/{@code Set<String>} 的既有纯函数（currentStreak 等）零成本喂入。</p>
 *
 * <p>只读：构造后不提供修改入口。写打卡走仓库的 upsertCheckIn/removeCheckIn 直连记录表。</p>
 */
public final class HabitWithCheckIns {

    private final HabitItem habit;
    private final List<CheckInRecord> records;

    // 惰性派生缓存：首次访问时按 records 计算，避免每次调用重算。
    private Set<String> datesCache;
    private Map<String, String> notesCache;

    public HabitWithCheckIns(HabitItem habit, List<CheckInRecord> records) {
        this.habit = habit;
        this.records = records == null ? new ArrayList<>() : records;
    }

    /** 组合视图承载的习惯实体（元数据：标题/分类/目标周期/归属等）。 */
    public HabitItem habit() {
        return habit;
    }

    /** 原始打卡记录列表（保真含 mood/duration/photo），只读。 */
    public List<CheckInRecord> records() {
        return records;
    }

    /**
     * 打卡日期集合（yyyy-MM-dd），去重、保序（按 records 迭代顺序，DAO 已 ORDER BY date）。
     * 供接收 {@code Set<String>} 的纯函数（如 buildMonthCells）直接使用。
     */
    public Set<String> dates() {
        if (datesCache == null) {
            Set<String> result = new LinkedHashSet<>();
            for (CheckInRecord record : records) {
                String date = record.getDate();
                if (date != null && !date.isEmpty()) {
                    result.add(date);
                }
            }
            datesCache = result;
        }
        return datesCache;
    }

    /**
     * 打卡日期列表，供接收 {@code List<String>} 的既有纯函数（currentStreak/longestStreak）直接喂入。
     * 每次返回新列表副本，调用方可安全改动而不影响本视图。
     */
    public List<String> dateList() {
        return new ArrayList<>(dates());
    }

    /** 某天的打卡备注；无记录或无备注时返回空串（不返回 null，便于调用方直接展示）。 */
    public String note(String date) {
        if (date == null) {
            return "";
        }
        String note = notes().get(date);
        return note == null ? "" : note;
    }

    /**
     * 日期 -> 备注 Map（仅含有非空备注的日期），惰性派生并缓存。
     */
    public Map<String, String> notes() {
        if (notesCache == null) {
            Map<String, String> result = new LinkedHashMap<>();
            for (CheckInRecord record : records) {
                String date = record.getDate();
                String note = record.getNote();
                if (date != null && !date.isEmpty() && note != null && !note.trim().isEmpty()) {
                    result.put(date, note.trim());
                }
            }
            notesCache = result;
        }
        return notesCache;
    }

    /** 便捷取习惯 id，等价于 {@code habit().getId()}。 */
    public long habitId() {
        return habit == null ? 0L : habit.getId();
    }
}
