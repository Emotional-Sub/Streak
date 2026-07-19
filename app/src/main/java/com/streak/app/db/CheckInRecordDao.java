package com.streak.app.db;

import androidx.room.Dao;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Upsert;

import com.streak.app.model.CheckInRecord;

import java.util.List;

/**
 * 打卡记录表数据访问对象。同步阻塞调用，线程调度由上层负责（同 {@link HabitDao}）。
 *
 * <p>业务唯一性靠 {@code (habitId, date)} 唯一索引：同一天再次 upsert 按唯一冲突更新当天那条，
 * 承接既有「一天一条打卡」的口径。跨习惯的引用完整性 v4 起由数据库的 FK ON DELETE CASCADE
 * 保证（删习惯自动删其记录），Repository 不必再手动清理。</p>
 *
 * <p><b>为什么用 {@code @Upsert}。</b>{@code @Insert(REPLACE)} 冲突时「先删后插」，会换掉自增主键；
 * {@code @Upsert} 先查后 INSERT/UPDATE，命中 (habitId,date) 时原地更新、保留主键，语义更贴合
 * 「覆盖当天记录」。</p>
 */
@Dao
public interface CheckInRecordDao {

    final class OwnedUpsertResult {
        private final boolean written;
        private final CheckInRecord previous;

        public OwnedUpsertResult(boolean written, CheckInRecord previous) {
            this.written = written;
            this.previous = previous;
        }

        public boolean wasWritten() {
            return written;
        }

        public CheckInRecord getPrevious() {
            return previous;
        }
    }

    @Query("SELECT * FROM check_in_records")
    List<CheckInRecord> getAll();

    /** 某习惯的全部打卡记录，按日期升序。 */
    @Query("SELECT * FROM check_in_records WHERE habitId = :habitId ORDER BY date ASC")
    List<CheckInRecord> getByHabit(long habitId);

    /**
     * 批量取多个习惯的打卡记录（消除 N+1）：readHabits 一次取全部记录后在内存按 habitId 分组，
     * 取代「读出习惯再逐个 getByHabit」的多次查询。空列表返回空结果。
     */
    @Query("SELECT * FROM check_in_records WHERE habitId IN (:habitIds) ORDER BY habitId ASC, date ASC")
    List<CheckInRecord> getByHabits(List<Long> habitIds);

    /**
     * 账号限定的批量取回：JOIN habits 按 ownerUsername 过滤，一条 SQL 取回该账号全部打卡记录。
     * 供 readHabits/readHabitsWithCheckIns 用——与「按 owner 取习惯」同处一个事务快照，
     * 杜绝「先查习惯、再无 owner 条件查打卡」的跨查询时间窗与账号越界。
     */
    @Query("SELECT r.* FROM check_in_records r "
            + "INNER JOIN habits h ON h.id = r.habitId "
            + "WHERE h.ownerUsername = :owner "
            + "ORDER BY r.habitId ASC, r.date ASC")
    List<CheckInRecord> getByOwner(String owner);

    /** 取某习惯某天的记录（唯一），无则 null。 */
    @Query("SELECT * FROM check_in_records WHERE habitId = :habitId AND date = :date LIMIT 1")
    CheckInRecord getByHabitAndDate(long habitId, String date);

    /** 账号限定读取：归属校验与记录查询由同一条 SQL 完成，避免先验 owner 的时间窗口。 */
    @Query("SELECT r.* FROM check_in_records r "
            + "INNER JOIN habits h ON h.id = r.habitId "
            + "WHERE r.habitId = :habitId AND r.date = :date "
            + "AND h.ownerUsername = :owner LIMIT 1")
    CheckInRecord getByHabitAndDateForOwner(long habitId, String date, String owner);

    /** 账号限定列表读取。 */
    @Query("SELECT r.* FROM check_in_records r "
            + "INNER JOIN habits h ON h.id = r.habitId "
            + "WHERE r.habitId = :habitId AND h.ownerUsername = :owner "
            + "ORDER BY r.date ASC")
    List<CheckInRecord> getByHabitForOwner(long habitId, String owner);

    @Query("SELECT COUNT(*) FROM check_in_records")
    int count();

    /**
     * upsert 单条：命中 (habitId, date) 唯一约束时原地 UPDATE 当天那条，否则 INSERT，
     * 即「同一天覆盖」。{@code @Upsert} 保留已有主键，不像 REPLACE 那样先删后插换新 id。
     */
    @Upsert
    void upsert(CheckInRecord record);

    /**
     * 在同一事务里读取旧记录并写入新值，返回被替换前的快照。
     * 若当天已有记录，沿用其自增主键，确保 {@code @Upsert} 执行 UPDATE 而非撞唯一索引。
     */
    @Transaction
    default OwnedUpsertResult upsertAndReturnPreviousForOwner(CheckInRecord record, String owner) {
        // 无条件覆盖：读旧记录、沿用其主键、直接 upsert（不做照片乐观校验）。
        CheckInRecord existing = getByHabitAndDateForOwner(
                record.getHabitId(), record.getDate(), owner);
        if (existing == null && !isHabitOwnedBy(record.getHabitId(), owner)) {
            return new OwnedUpsertResult(false, null);
        }
        if (existing != null) {
            record.setId(existing.getId());
        }
        upsert(record);
        return new OwnedUpsertResult(true, existing);
    }

    /**
     * 带照片乐观并发校验的直写：在同一事务里读旧记录、比对期望原照片，再写入。
     *
     * <p><b>photoChanged=false（本次没改照片）：</b>保留数据库当前最新照片，避免用页面加载时的
     * 过期照片快照覆盖别人刚换的新图（record 的 photoUri 会被忽略，沿用 existing 的）。</p>
     *
     * <p><b>photoChanged=true（本次改了照片）：</b>要求数据库里当前照片仍等于页面加载时看到的
     * expectedOriginalPhotoUri；若已被并发替换（不相等），<b>原子拒绝本次写入</b>（返回 wasWritten=false，
     * 不 upsert），由调用方清理本次新拷贝的临时图，杜绝旧快照覆盖并发新图。</p>
     */
    @Transaction
    default OwnedUpsertResult upsertAndReturnPreviousForOwner(
            CheckInRecord record, String owner,
            String expectedOriginalPhotoUri, boolean photoChanged) {
        CheckInRecord existing = getByHabitAndDateForOwner(
                record.getHabitId(), record.getDate(), owner);
        if (existing == null
                && !isHabitOwnedBy(record.getHabitId(), owner)) {
            return new OwnedUpsertResult(false, null);
        }
        String currentPhoto = existing == null ? null : existing.getPhotoUri();
        if (photoChanged) {
            // 改了照片：数据库当前照片必须仍是页面加载时的那张，否则并发已替换 -> 原子拒绝。
            if (!java.util.Objects.equals(currentPhoto, expectedOriginalPhotoUri)) {
                return new OwnedUpsertResult(false, existing);
            }
        } else {
            // 没改照片：保留数据库当前最新照片，不让过期快照覆盖并发新图。
            record.setPhotoUri(currentPhoto);
        }
        if (existing != null) {
            record.setId(existing.getId());
        }
        upsert(record);
        return new OwnedUpsertResult(true, existing);
    }

    @Upsert
    void upsertAll(List<CheckInRecord> records);

    /** 删某习惯某天的打卡（撤销打卡用）。 */
    @Query("DELETE FROM check_in_records WHERE habitId = :habitId AND date = :date")
    int deleteByHabitAndDate(long habitId, String date);

    /**
     * 在同一事务里读取并删除目标记录，返回实际被删除的快照。
     * 避免 Repository 分两次调用时，夹在 SELECT/DELETE 之间的并发更新导致清错照片。
     */
    @Transaction
    default CheckInRecord deleteAndReturnByHabitAndDateForOwner(
            long habitId, String date, String owner) {
        CheckInRecord existing = getByHabitAndDateForOwner(habitId, date, owner);
        if (existing == null) {
            return null;
        }
        return deleteByHabitAndDateForOwner(habitId, date, owner) > 0 ? existing : null;
    }

    @Query("SELECT EXISTS(SELECT 1 FROM habits WHERE id = :habitId AND ownerUsername = :owner)")
    boolean isHabitOwnedBy(long habitId, String owner);

    @Query("DELETE FROM check_in_records WHERE habitId = :habitId AND date = :date "
            + "AND EXISTS(SELECT 1 FROM habits WHERE id = :habitId AND ownerUsername = :owner)")
    int deleteByHabitAndDateForOwner(long habitId, String date, String owner);

    /**
     * 删某习惯的全部打卡记录。v4 起删习惯已由 FK CASCADE 自动清理，本方法保留供
     * 过渡期的整表替换/导入路径（先清空再写）等非「删父行」场景显式使用。
     */
    @Query("DELETE FROM check_in_records WHERE habitId = :habitId")
    void deleteByHabit(long habitId);

    /** 删某账号名下所有习惯的打卡记录（过渡期显式清理用；删号删习惯行时 CASCADE 亦会清理）。 */
    @Query("DELETE FROM check_in_records WHERE habitId IN "
            + "(SELECT id FROM habits WHERE ownerUsername = :owner)")
    void deleteByOwner(String owner);

    /**
     * 跨三表 SQL EXISTS 精确匹配某图片 URI 是否仍被引用（习惯封面 / 打卡照片 / 账号头像）。
     * 供删图前的引用检查走快速路径，避免在主线程物化三表逐条算 canonicalPath。
     * 精确字符串匹配即可覆盖绝大多数场景（URI 统一由 Uri.fromFile 生成、格式一致）；
     * 跨格式（file:// vs 裸路径）的少见差异由调用方的 canonical 兜底补齐。
     */
    @Query("SELECT EXISTS(SELECT 1 FROM habits WHERE imageUri = :uri) "
            + "OR EXISTS(SELECT 1 FROM check_in_records WHERE photoUri = :uri) "
            + "OR EXISTS(SELECT 1 FROM accounts WHERE avatarUri = :uri)")
    boolean isImageUriReferenced(String uri);

    @Query("DELETE FROM check_in_records")
    void clear();
}
