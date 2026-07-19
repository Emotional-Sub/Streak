package com.streak.app.model;

import java.util.ArrayList;
import java.util.Collections;
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
 * 派生，供接收 {@code List<String>}/{@code Set<String>} 的既有纯函数（currentStreak 等）零成本喂入。</p>
 *
 * <p><b>深度只读契约（防御性快照）。</b>本类是「构造那一刻」记录状态的不可变快照，且保证
 * <b>深度</b>只读——不仅返回的集合不可变，集合里的 {@link CheckInRecord} 也是防御性副本：</p>
 * <ul>
 *   <li>习惯元数据及其 tags/completedDates/notes 集合在构造时深拷贝，{@link #habit()} 也只返回副本；</li>
 *   <li>构造时对传入的 {@code records} 列表<b>逐条深拷贝</b>并冻结，之后外部再改动原列表或原记录对象，
 *       都不会影响本视图（{@link CheckInRecord} 本身是可变的，只包一层 unmodifiableList 挡不住
 *       {@code record.setX()} 的篡改，故必须深拷贝）；</li>
 *   <li>{@link #records()} 每次返回元素已深拷贝的不可变列表，调用方不能改写集合，也拿不到内部记录对象；</li>
 *   <li>{@link #dates()}/{@link #notes()} 返回<b>不可变</b>集合，且由构造期快照一次算好（无惰性可变缓存），
 *       杜绝「缓存已建好、之后 records 被改导致缓存与记录不一致」的自相矛盾。</li>
 * </ul>
 *
 * <p>写打卡走仓库的 upsertCheckIn/removeCheckIn 直连记录表，再重新读取得到新的快照视图。</p>
 */
public final class HabitWithCheckIns {

    private final HabitItem habit;
    // 构造期即冻结的记录快照（每个元素都是深拷贝，与外部传入的对象脱钩）。
    private final List<CheckInRecord> records;
    // 构造期一次算好的不可变派生视图——不做惰性可变缓存，避免与 records 失同步。
    private final Set<String> dates;
    private final Map<String, String> notes;

    public HabitWithCheckIns(HabitItem habit, List<CheckInRecord> records) {
        this.habit = copyOf(habit);
        // 深拷贝每条记录后冻结列表：外部后续改动原列表/原记录对象都不影响本快照。
        List<CheckInRecord> snapshot = new ArrayList<>();
        Set<String> dateSet = new LinkedHashSet<>();
        Map<String, String> noteMap = new LinkedHashMap<>();
        if (records != null) {
            for (CheckInRecord record : records) {
                if (record == null) {
                    continue;
                }
                CheckInRecord recordCopy = copyOf(record);
                snapshot.add(recordCopy);
                String date = recordCopy.getDate();
                if (date != null && !date.isEmpty()) {
                    dateSet.add(date);
                    String note = recordCopy.getNote();
                    if (note != null && !note.trim().isEmpty()) {
                        noteMap.put(date, note.trim());
                    }
                }
            }
        }
        this.records = Collections.unmodifiableList(snapshot);
        this.dates = Collections.unmodifiableSet(dateSet);
        this.notes = Collections.unmodifiableMap(noteMap);
    }

    /** 深拷贝一条记录，使快照与外部对象完全脱钩（CheckInRecord 可变，浅引用挡不住 setX 篡改）。 */
    private static CheckInRecord copyOf(CheckInRecord source) {
        CheckInRecord copy = new CheckInRecord();
        copy.setId(source.getId());
        copy.setHabitId(source.getHabitId());
        copy.setDate(source.getDate());
        copy.setNote(source.getNote());
        copy.setMood(source.getMood());
        copy.setDurationMinutes(source.getDurationMinutes());
        copy.setPhotoUri(source.getPhotoUri());
        return copy;
    }

    /** 深拷贝习惯元数据及其集合字段，避免外部修改破坏快照的 id/归属。 */
    private static HabitItem copyOf(HabitItem source) {
        if (source == null) {
            return null;
        }
        HabitItem copy = new HabitItem();
        copy.setId(source.getId());
        copy.setTitle(source.getTitle());
        copy.setContent(source.getContent());
        copy.setReminderTime(source.getReminderTime());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setImageUri(source.getImageUri());
        copy.setCategory(source.getCategory());
        copy.setTags(new ArrayList<>(source.getTags()));
        copy.setCompletedDates(new ArrayList<>(source.getCompletedDates()));
        copy.setReminderEnabled(source.isReminderEnabled());
        copy.setWeeklyTarget(source.getWeeklyTarget());
        copy.setNotes(new LinkedHashMap<>(source.getNotes()));
        copy.setOwnerUsername(source.getOwnerUsername());
        return copy;
    }

    /** 组合视图承载的习惯元数据。每次返回深拷贝，修改它不会影响本快照。 */
    public HabitItem habit() {
        return copyOf(habit);
    }

    /**
     * 原始打卡记录列表（保真含 mood/duration/photo）。每次返回不可变的新列表，
     * 且元素也是深拷贝——调用方拿不到本视图内部的记录对象。
     */
    public List<CheckInRecord> records() {
        List<CheckInRecord> copy = new ArrayList<>(records.size());
        for (CheckInRecord record : records) {
            copy.add(copyOf(record));
        }
        return Collections.unmodifiableList(copy);
    }

    /**
     * 打卡日期集合（yyyy-MM-dd），去重、保序（按 records 迭代顺序，DAO 已 ORDER BY date）。
     * 返回<b>不可变</b>集合，供接收 {@code Set<String>} 的纯函数（如 buildMonthCells）直接使用。
     */
    public Set<String> dates() {
        return dates;
    }

    /**
     * 打卡日期列表，供接收 {@code List<String>} 的既有纯函数（currentStreak/longestStreak）直接喂入。
     * 每次返回不可变列表副本。
     */
    public List<String> dateList() {
        return Collections.unmodifiableList(new ArrayList<>(dates));
    }

    /** 某天的打卡备注；无记录或无备注时返回空串（不返回 null，便于调用方直接展示）。 */
    public String note(String date) {
        if (date == null) {
            return "";
        }
        String note = notes.get(date);
        return note == null ? "" : note;
    }

    /**
     * 日期 -> 备注 Map（仅含有非空备注的日期），返回<b>不可变</b>视图。
     */
    public Map<String, String> notes() {
        return notes;
    }

    /** 便捷取习惯 id，等价于 {@code habit().getId()}。 */
    public long habitId() {
        return habit == null ? 0L : habit.getId();
    }
}
