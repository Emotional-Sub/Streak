package com.streak.app.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 单次打卡记录（规范化后的打卡真相源）。
 *
 * <p><b>设计取舍：为什么独立成表。</b>早期把打卡日期塞进 {@link HabitItem#getCompletedDates()}
 * （List&lt;String&gt; 序列化成一列 JSON），备注塞进 notes（Map 一列 JSON）。这样存储虽简单，
 * 但无法用 SQL 统计、无法加 (habitId, date) 唯一约束、也难以自然扩展「心情/耗时/打卡照片」。
 * 拆成 habits 1:N check_in_records 后，每天一行、字段规范，统计可直接走 SQL/聚合。</p>
 *
 * <p><b>唯一口径：{@code (habitId, date)} 唯一——一天一条。</b>全 App 既有统计（连续天数、
 * 完成率、热力图）历来都对打卡日期 {@code new HashSet<>(...)} 去重，即「一天最多算一次」。
 * 保留该口径为唯一约束，既不改变任何既有统计语义，又给出干净的关系约束。mood/duration/note/photo
 * 是「当天这条打卡」的附加属性，同一天再次打卡按 upsert 覆盖当天记录。</p>
 *
 * <p><b>外键 CASCADE（v4 起）。</b>{@link com.streak.app.db.HabitDao} 的 upsert 早期用
 * {@code @Insert(REPLACE)}（INSERT OR REPLACE，按主键先删后插），那会让「编辑/打卡习惯」时的
 * upsert 触发 FK CASCADE 误删该习惯的全部打卡记录，故当时刻意不加 FK、由 Repository 手动维护
 * 引用完整性。v4 起 Habit 与本表的 upsert 都改用 Room 的 {@code @Upsert}（先查后
 * INSERT 或 UPDATE，主键行不被删除），CASCADE 不再会被日常 upsert 误触发，因此这里加上真正的
 * {@code @ForeignKey} ON DELETE CASCADE：删习惯时数据库自动删其打卡记录，Repository 不必再手动清理。</p>
 */
@Entity(
        tableName = "check_in_records",
        foreignKeys = @ForeignKey(
                entity = HabitItem.class,
                parentColumns = "id",
                childColumns = "habitId",
                onDelete = ForeignKey.CASCADE
        ),
        indices = {
                // (habitId, date) 唯一：一天一条，冲突即覆盖（承接既有「按天去重」口径）。
                @Index(value = {"habitId", "date"}, unique = true),
                // 按习惯查记录是最高频访问（详情页/统计/删除），单独建索引。
                @Index(value = {"habitId"})
        }
)
public class CheckInRecord {

    // 自增主键：记录本身没有天然唯一 id，交给 Room 自增。业务唯一性由 (habitId, date) 索引保证。
    @PrimaryKey(autoGenerate = true)
    private long id;

    // 所属习惯 id（对应 habits.id）。
    private long habitId;

    // 打卡日期，格式 yyyy-MM-dd（与既有 completedDates 元素、HabitUtils.today() 完全一致）。
    @NonNull
    private String date = "";

    // 当天打卡备注/心情文本，可空。承接旧 notes Map 的 value。
    private String note;

    // 心情：0=未记录；1..5 表示由差到好的心情等级（UI 用表情映射）。
    private int mood;

    // 本次打卡耗时（分钟）。0 表示未记录。
    private int durationMinutes;

    // 本次打卡照片 file:// uri，可空。与习惯自身的 imageUri 独立（这是「当天打卡」的照片）。
    private String photoUri;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getHabitId() {
        return habitId;
    }

    public void setHabitId(long habitId) {
        this.habitId = habitId;
    }

    @NonNull
    public String getDate() {
        return date == null ? "" : date;
    }

    public void setDate(@NonNull String date) {
        this.date = date == null ? "" : date;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public int getMood() {
        return mood;
    }

    public void setMood(int mood) {
        this.mood = mood;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(int durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public String getPhotoUri() {
        return photoUri;
    }

    public void setPhotoUri(String photoUri) {
        this.photoUri = photoUri;
    }
}
