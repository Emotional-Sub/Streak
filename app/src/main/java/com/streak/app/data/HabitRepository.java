package com.streak.app.data;

import androidx.annotation.Nullable;

import com.streak.app.db.HabitDao;
import com.streak.app.db.StreakDatabase;
import com.streak.app.model.CheckInRecord;
import com.streak.app.model.HabitItem;
import com.streak.app.model.HabitWithCheckIns;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 习惯仓库（Phase B 从 {@code AppRepository} 拆出）：习惯的增删改查与账号隔离。
 *
 * <p><b>职责边界。</b>只负责 habits 表的读写，且全部限定在「当前登录账号」范围内。
 * 读习惯时委托 {@link CheckInRepository#aggregateInto} 把打卡记录聚合回填进 completedDates/notes
 * 只读投影，供既有消费端读取。<b>saveHabit 只写习惯元数据行、不回写打卡记录</b>——打卡的增删改
 * 一律由直写 API（{@code upsertCheckIn}/{@code removeCheckIn}）独占落到记录表。仅 {@link #writeHabits}
 * （整机备份恢复的整表替换）仍用 {@link CheckInRepository#syncFrom} 从派生字段重建记录。</p>
 *
 * <p><b>当前账号来源。</b>为避免与 Auth 层循环依赖，当前用户名由构造时注入的
 * {@link Supplier} 提供（{@code AppRepository} 传入 {@code this::getCurrentUser}），
 * 本类不直接读 SharedPreferences。</p>
 *
 * <p><b>业务边界收紧（Phase B）。</b>{@link #findHabitById} 与 {@link #saveHabit} 现在
 * 都限定当前账号：跨账号按 id 读返回 null、保存他账号归属的对象直接拒绝。
 * 详见各方法注释——这是相对旧 {@code AppRepository} 的行为变更。</p>
 */
public class HabitRepository {

    private final StreakDatabase database;
    private final HabitDao habitDao;
    private final CheckInRepository checkInRepository;
    private final Supplier<String> currentUserSupplier;

    public HabitRepository(StreakDatabase database, HabitDao habitDao,
                           CheckInRepository checkInRepository,
                           Supplier<String> currentUserSupplier) {
        this.database = database;
        this.habitDao = habitDao;
        this.checkInRepository = checkInRepository;
        this.currentUserSupplier = currentUserSupplier;
    }

    private String currentUser() {
        String owner = currentUserSupplier.get();
        return owner == null ? "" : owner;
    }

    /**
     * 读当前登录账号的全部习惯，并把各自打卡记录聚合回填进内存派生字段（消除 N+1：
     * 一次批量取回这批习惯的全部记录，在内存按 habitId 分组回填）。
     */
    public List<HabitItem> readHabits() {
        String owner = currentUser();
        if (owner.isEmpty()) {
            return new ArrayList<>();
        }
        // 习惯与打卡在同一事务快照内读取：先按 owner 取习惯，再按 owner JOIN 取打卡，
        // 两次查询都限定当前账号且同处一个事务，杜绝跨查询时间窗与账号越界。
        return database.runInTransaction((java.util.concurrent.Callable<List<HabitItem>>) () -> {
            List<HabitItem> habits = habitDao.getByOwner(owner);
            if (habits == null) {
                return new ArrayList<HabitItem>();
            }
            Map<Long, List<CheckInRecord>> byHabit =
                    checkInRepository.recordsGroupedByOwner(owner);
            for (HabitItem habit : habits) {
                checkInRepository.aggregateInto(habit, byHabit.get(habit.getId()));
            }
            return new ArrayList<HabitItem>(habits);
        });
    }

    /**
     * 读当前登录账号的全部习惯，每条打包成 {@link HabitWithCheckIns} 只读组合视图返回。
     *
     * <p>与 {@link #readHabits} 共用同一套批量查询（一次 {@code recordsGroupedByHabit} 消除 N+1），
     * 区别在于不把记录回填进 {@code HabitItem} 的派生字段，而是连同原始 {@link CheckInRecord} 列表
     * （保真含 mood/duration/photo）打包，交给统计/分析/UI 消费端。这是 completedDates/notes 只读
     * 投影的替代读取入口：新消费端应优先用本方法，逐步替换对 {@code HabitItem} 派生字段的依赖。</p>
     */
    public List<HabitWithCheckIns> readHabitsWithCheckIns() {
        String owner = currentUser();
        if (owner.isEmpty()) {
            return new ArrayList<>();
        }
        // 同事务快照：owner 取习惯 + owner JOIN 取打卡，两查询同处一个事务、均限定当前账号。
        return database.runInTransaction((java.util.concurrent.Callable<List<HabitWithCheckIns>>) () -> {
            List<HabitItem> habits = habitDao.getByOwner(owner);
            if (habits == null) {
                return new ArrayList<HabitWithCheckIns>();
            }
            Map<Long, List<CheckInRecord>> byHabit =
                    checkInRepository.recordsGroupedByOwner(owner);
            List<HabitWithCheckIns> result = new ArrayList<>(habits.size());
            for (HabitItem habit : habits) {
                result.add(new HabitWithCheckIns(habit, byHabit.get(habit.getId())));
            }
            return result;
        });
    }

    /**
     * 整体替换「当前账号」的习惯：清空该账号旧数据后批量写入，事务保证一致性，
     * 且绝不触碰其它账号的习惯。写入前给每条盖上归属，避免导入/构造的数据漏了 owner。
     */
    public void writeHabits(List<HabitItem> habits) {
        String owner = currentUser();
        if (owner.isEmpty()) {
            return;
        }
        final List<HabitItem> scoped = new ArrayList<>();
        // 预校验（不修改任何传入对象）：拒绝 null、重复 id、以及归属明确写着他账号的习惯。
        // 归属为空的留到事务内、校验通过后再补盖当前账号，避免批次被拒时已污染调用方对象。
        Set<Long> ids = new HashSet<>();
        if (habits != null) {
            for (HabitItem habit : habits) {
                if (habit == null || habit.getId() <= 0 || !ids.add(habit.getId())) {
                    return;
                }
                String habitOwner = habit.getOwnerUsername();
                if (habitOwner != null && !habitOwner.isEmpty() && !owner.equals(habitOwner)) {
                    return;
                }
            }
            for (HabitItem habit : habits) {
                HabitItem copy = copyHabit(habit);
                if (copy.getOwnerUsername() == null || copy.getOwnerUsername().isEmpty()) {
                    copy.setOwnerUsername(owner);
                }
                scoped.add(copy);
            }
        }
        // 所有「按 id 的归属检查」与整表替换必须在同一事务里，保证原子性：
        // 先在事务内逐个校验没有习惯 id 被其它账号占用；任一被占用即抛异常，
        // Room 回滚整笔事务。因删除/盖归属都发生在校验通过之后，被拒时既不会先清空
        // 当前账号数据，也不会部分修改传入对象。校验全过后才盖归属并整表替换，
        // 再按每个习惯的派生字段重建打卡记录（仅备份恢复路径用 syncFrom）。
        List<String> replacedImages = new ArrayList<>();
        try {
            database.runInTransaction(() -> {
                for (HabitItem habit : scoped) {
                    HabitItem existing = habitDao.findById(habit.getId());
                    if (existing != null && !owner.equals(existing.getOwnerUsername())) {
                        throw new WriteRejectedException();
                    }
                }
                List<HabitItem> existingHabits = habitDao.getByOwner(owner);
                if (existingHabits != null) {
                    for (HabitItem existing : existingHabits) {
                        if (existing.getImageUri() != null
                                && !existing.getImageUri().trim().isEmpty()) {
                            replacedImages.add(existing.getImageUri());
                        }
                        replacedImages.addAll(
                                checkInRepository.getCheckInPhotoUris(existing.getId()));
                    }
                }
                habitDao.replaceAllForOwner(owner, scoped);
                for (HabitItem habit : scoped) {
                    checkInRepository.syncFrom(habit);
                }
            });
            checkInRepository.deletePhotos(replacedImages);
        } catch (WriteRejectedException rejected) {
            // 批次含被他账号占用的 id：事务已回滚，DB 与传入对象均保持原状。
        }
    }

    /** 内部哨兵异常：用于在 writeHabits 事务内触发原子回滚（批次因归属冲突被整体拒绝）。 */
    private static final class WriteRejectedException extends RuntimeException {
        WriteRejectedException() {
            super(null, null, false, false);
        }
    }

    private HabitItem copyHabit(HabitItem source) {
        return new HabitItem(source);
    }

    /**
     * 按 id 读习惯（含打卡聚合回填），<b>限定当前账号</b>。
     *
     * <p><b>业务边界（Phase B 收紧）。</b>旧实现只按 id 读、不校验归属，跨账号可读到他人习惯。
     * 现加 owner 过滤：非当前账号的 id 返回 null，与 {@link #readHabits} 的隔离口径一致。
     * 对合法场景透明——提醒只在归属账号登录态下被调度（登出取消、登录重排），
     * 后台闹钟触发时读到的习惯必属当前账号；真正的陈旧跨账号闹钟读到 null 走既有「习惯已删」静默分支。</p>
     */
    @Nullable
    public HabitItem findHabitById(long habitId) {
        String owner = currentUser();
        if (owner.isEmpty()) {
            return null;
        }
        // 习惯与打卡在同一事务快照内读取，且打卡按 owner JOIN 限定，不做无 owner 条件查询。
        return database.runInTransaction((java.util.concurrent.Callable<HabitItem>) () -> {
            HabitItem habit = habitDao.findById(habitId);
            if (habit == null || !owner.equals(habit.getOwnerUsername())) {
                return null; // 不存在或非当前账号：按数据隔离不返回
            }
            List<CheckInRecord> records =
                    checkInRepository.getCheckInsForOwnerAsc(habitId, owner);
            checkInRepository.aggregateInto(habit, records);
            return habit;
        });
    }

    /**
     * 校验某习惯是否归属当前登录账号——打卡接口（getCheckIn/getCheckIns/upsertCheckIn/
     * removeCheckIn）的账号隔离守卫。
     *
     * <p><b>为什么需要。</b>打卡的读写撤销此前只按 habitId 定位记录，不校验该习惯属于谁，
     * 与「严格数据隔离」不符：拿到他账号的 habitId 即可读/改/撤其打卡。本方法让打卡入口
     * 也限定当前账号。比 {@link #findHabitById} 轻——只查归属、不做打卡聚合回填。</p>
     */
    public boolean isOwnedByCurrentUser(long habitId) {
        String owner = currentUser();
        if (owner.isEmpty()) {
            return false;
        }
        HabitItem habit = habitDao.findById(habitId);
        return habit != null && owner.equals(habit.getOwnerUsername());
    }

    /**
     * 生成全表唯一的习惯 id（以当前毫秒为基准，占用则递增）。
     * id 是全表主键，必须对整表防撞——不能只在当前账号内查，否则两个账号在同一毫秒
     * 新建可能撞 id，upsert 会跨账号覆盖，破坏数据隔离。
     */
    public long generateUniqueHabitId() {
        long id = System.currentTimeMillis();
        while (habitDao.existsById(id)) {
            id++;
        }
        return id;
    }

    /**
     * 保存单个习惯（新增或更新，按 id 主键 upsert）。
     *
     * <p><b>业务边界（Phase B 收紧）。</b>未设归属的新习惯盖上当前账号；<b>拒绝保存归属他账号的对象</b>
     * ——若传入习惯的 ownerUsername 非空且不等于当前账号，直接返回不写，防止越权改写他人数据。</p>
     */
    public boolean saveHabit(HabitItem habit) {
        if (habit == null || habit.getId() <= 0) {
            return false;
        }
        String owner = currentUser();
        if (owner.isEmpty()) {
            return false;
        }
        HabitItem candidate = copyHabit(habit);
        String habitOwner = candidate.getOwnerUsername();
        if (habitOwner != null && !habitOwner.isEmpty() && !owner.equals(habitOwner)) {
            return false; // 归属他账号：拒绝保存（数据隔离防御）
        }
        // 只 upsert 习惯行本身。打卡记录不在此同步：编辑习惯只改标题/分类/提醒等元信息，
        // 不碰打卡；打卡的增删改由直写 API（upsertCheckIn/removeCheckIn）独占落到 check_in_records
        // 表。completedDates/notes 已是「读时聚合」的只读投影，不再作为写入路径回写。
        String[] replacedImage = {null};
        boolean[] saved = {false};
        database.runInTransaction(() -> {
            HabitItem existing = habitDao.findById(candidate.getId());
            if (existing == null || owner.equals(existing.getOwnerUsername())) {
                if (existing != null
                        && !java.util.Objects.equals(
                        existing.getImageUri(), candidate.getImageUri())) {
                    replacedImage[0] = existing.getImageUri();
                }
                if (candidate.getOwnerUsername() == null || candidate.getOwnerUsername().isEmpty()) {
                    candidate.setOwnerUsername(owner);
                }
                habitDao.upsert(candidate);
                saved[0] = true;
            }
        });
        if (saved[0]) {
            checkInRepository.deletePhotoIfUnreferenced(replacedImage[0]);
        }
        return saved[0];
    }

    /**
     * 保存习惯元数据（带图片乐观并发校验）——编辑页专用，防旧快照覆盖并发新图。
     *
     * <p><b>imageChanged=false（本页没改图）：</b>保留数据库当前最新 imageUri，不用页面加载时的
     * 过期图覆盖别处刚存的新图。</p>
     *
     * <p><b>imageChanged=true（本页改了图）：</b>要求数据库当前 imageUri 仍等于页面加载时看到的
     * expectedOriginalImageUri；若已被并发替换，则<b>原子拒绝本次保存</b>（返回 false，不 upsert、
     * 不删除任何图片，尤其不删本次新图），由调用方提示重试。</p>
     *
     * <p>新建习惯（数据库无此 id）不涉并发，直接按新值写入。</p>
     */
    public boolean saveHabit(HabitItem habit, String expectedOriginalImageUri,
                             boolean imageChanged) {
        if (habit == null || habit.getId() <= 0) {
            return false;
        }
        String owner = currentUser();
        if (owner.isEmpty()) {
            return false;
        }
        HabitItem candidate = copyHabit(habit);
        String habitOwner = candidate.getOwnerUsername();
        if (habitOwner != null && !habitOwner.isEmpty() && !owner.equals(habitOwner)) {
            return false; // 归属他账号：拒绝
        }
        String[] replacedImage = {null};
        boolean[] saved = {false};
        database.runInTransaction(() -> {
            HabitItem existing = habitDao.findById(candidate.getId());
            if (existing != null && !owner.equals(existing.getOwnerUsername())) {
                return; // 归属他账号：拒绝
            }
            if (existing != null) {
                String dbImage = existing.getImageUri();
                if (!imageChanged) {
                    // 本页没改图：保留数据库当前最新图，不覆盖。
                    candidate.setImageUri(dbImage);
                } else {
                    // 本页改了图：数据库当前图必须仍是加载时的原图，否则并发已替换 -> 拒绝。
                    if (!java.util.Objects.equals(dbImage, expectedOriginalImageUri)) {
                        return; // saved 保持 false；不 upsert、不删任何图（含本次新图）
                    }
                    if (!java.util.Objects.equals(dbImage, candidate.getImageUri())) {
                        replacedImage[0] = dbImage; // 换图成功，旧图待清理
                    }
                }
            }
            if (candidate.getOwnerUsername() == null || candidate.getOwnerUsername().isEmpty()) {
                candidate.setOwnerUsername(owner);
            }
            habitDao.upsert(candidate);
            saved[0] = true;
        });
        if (saved[0]) {
            checkInRepository.deletePhotoIfUnreferenced(replacedImage[0]);
        }
        return saved[0];
    }

    /**
     * 按 id 删除单个习惯，并限定归属为当前账号。只动一行，不整表覆盖，避免并发丢更新。
     * 带 owner 过滤是数据隔离的防御措施：即便传入其它账号的 id 也删不到，不会跨账号误删。
     *
     * <p>v4 起 check_in_records 有 FK ON DELETE CASCADE，删习惯行即自动删其打卡记录。
     * 删除提交后再按全库引用检查清理习惯图和打卡照片，避免共享文件被误删；文件清理失败
     * 也不会影响已经提交的数据库状态。</p>
     */
    public boolean deleteHabitById(long habitId) {
        String owner = currentUser();
        if (owner.isEmpty()) {
            return false;
        }
        List<String> imagesToDelete = new ArrayList<>();
        final int[] deleted = {0};
        database.runInTransaction(() -> {
            HabitItem target = habitDao.findById(habitId);
            if (target == null || !owner.equals(target.getOwnerUsername())) {
                return;
            }
            if (target.getImageUri() != null && !target.getImageUri().trim().isEmpty()) {
                imagesToDelete.add(target.getImageUri());
            }
            imagesToDelete.addAll(checkInRepository.getCheckInPhotoUris(habitId));
            deleted[0] = habitDao.deleteByIdForOwner(habitId, owner);
        });
        if (deleted[0] > 0) {
            checkInRepository.deletePhotos(imagesToDelete);
            return true;
        }
        return false;
    }

    /**
     * 认领「孤儿习惯」，修复旧数据永久固定 student 的问题。
     *
     * <p><b>为什么需要。</b>旧 JSON 时代习惯无归属，迁移/导入时统一固定归给演示账号 student；
     * 删号时若残留了归属已删账号的习惯，这些习惯也会成为「登不进去的账号」名下的孤儿数据，
     * 用户永远看不到。原实现把归属永久钉死在 student，非 student 用户即使是这些数据的真实主人也拿不回。</p>
     *
     * <p><b>孤儿定义。</b>习惯的 {@code ownerUsername} 为空，<b>或</b>其归属账号已不在账号表里
     * （{@code validOwners} 传入所有仍存在的用户名）。归属账号仍存在的习惯（含 student 的演示数据、
     * 其它账号的正常数据）<b>绝不认领</b>，避免跨账号窃取。</p>
     *
     * <p><b>认领时机与策略。</b>由门面在登录成功后调用，把孤儿习惯改归当前登录账号。
     * 数据总比丢失好，且「谁先登录谁认领无主数据」是无后端本地应用的合理兜底。
     * 认领与打卡记录无关（记录靠 habitId 关联，不随 owner 变化）。返回认领的条数。</p>
     */
    public int claimOrphanHabits(String claimant, Set<String> validOwners) {
        if (claimant == null || claimant.isEmpty()) {
            return 0;
        }
        // 单条条件 UPDATE：只改 ownerUsername，其余列一律不动（不 getAll 回写整行，避免覆盖并发编辑）。
        // 孤儿判定（归属空/null 或不在有效账号集合内）与更新在同一条 SQL 里完成，天然原子。
        if (validOwners == null || validOwners.isEmpty()) {
            // 无有效账号集合：认领所有归属不等于 claimant 的习惯（含空/null 归属）。
            return database.runInTransaction(
                    (java.util.concurrent.Callable<Integer>) () ->
                            habitDao.claimAllNotOwnedBy(claimant));
        }
        List<String> owners = new ArrayList<>(validOwners);
        return database.runInTransaction(
                (java.util.concurrent.Callable<Integer>) () ->
                        habitDao.claimOrphans(claimant, owners));
    }
}
