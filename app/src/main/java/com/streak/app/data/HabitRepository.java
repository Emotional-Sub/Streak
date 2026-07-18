package com.streak.app.data;

import androidx.annotation.Nullable;

import com.streak.app.db.HabitDao;
import com.streak.app.db.StreakDatabase;
import com.streak.app.model.CheckInRecord;
import com.streak.app.model.HabitItem;
import com.streak.app.model.HabitWithCheckIns;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 习惯仓库（Phase B 从 {@code AppRepository} 拆出）：习惯的增删改查与账号隔离。
 *
 * <p><b>职责边界。</b>只负责 habits 表的读写，且全部限定在「当前登录账号」范围内。
 * 打卡记录的聚合回填/回写委托 {@link CheckInRepository}（读习惯时把记录物化进
 * completedDates/notes、写习惯时把这两个派生字段同步回记录表）。</p>
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
        List<HabitItem> habits = habitDao.getByOwner(owner);
        if (habits == null) {
            return new ArrayList<>();
        }
        List<Long> ids = new ArrayList<>(habits.size());
        for (HabitItem habit : habits) {
            ids.add(habit.getId());
        }
        Map<Long, List<CheckInRecord>> byHabit = checkInRepository.recordsGroupedByHabit(ids);
        for (HabitItem habit : habits) {
            checkInRepository.aggregateInto(habit, byHabit.get(habit.getId()));
        }
        return habits;
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
        List<HabitItem> habits = habitDao.getByOwner(owner);
        if (habits == null) {
            return new ArrayList<>();
        }
        List<Long> ids = new ArrayList<>(habits.size());
        for (HabitItem habit : habits) {
            ids.add(habit.getId());
        }
        Map<Long, List<CheckInRecord>> byHabit = checkInRepository.recordsGroupedByHabit(ids);
        List<HabitWithCheckIns> result = new ArrayList<>(habits.size());
        for (HabitItem habit : habits) {
            result.add(new HabitWithCheckIns(habit, byHabit.get(habit.getId())));
        }
        return result;
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
        List<HabitItem> scoped = habits == null ? new ArrayList<>() : habits;
        for (HabitItem habit : scoped) {
            if (habit.getOwnerUsername() == null || habit.getOwnerUsername().isEmpty()) {
                habit.setOwnerUsername(owner);
            }
        }
        // 习惯行与打卡记录在同一事务里替换：先整表替换该账号的习惯，
        // 再按每个习惯的内存派生字段同步其打卡记录，避免「习惯已换、记录未换」的半成品状态。
        database.runInTransaction(() -> {
            habitDao.replaceAllForOwner(owner, scoped);
            for (HabitItem habit : scoped) {
                checkInRepository.syncFrom(habit);
            }
        });
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
        HabitItem habit = habitDao.findById(habitId);
        if (habit == null) {
            return null;
        }
        if (owner.isEmpty() || !owner.equals(habit.getOwnerUsername())) {
            return null; // 非当前账号：按数据隔离不返回
        }
        checkInRepository.aggregateInto(habit);
        return habit;
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
    public void saveHabit(HabitItem habit) {
        if (habit == null) {
            return;
        }
        String owner = currentUser();
        if (owner.isEmpty()) {
            return;
        }
        String habitOwner = habit.getOwnerUsername();
        if (habitOwner == null || habitOwner.isEmpty()) {
            habit.setOwnerUsername(owner);
        } else if (!owner.equals(habitOwner)) {
            return; // 归属他账号：拒绝保存（数据隔离防御）
        }
        // 只 upsert 习惯行本身。打卡记录不在此同步：编辑习惯只改标题/分类/提醒等元信息，
        // 不碰打卡；打卡的增删改由直写 API（upsertCheckIn/removeCheckIn）独占落到 check_in_records
        // 表。completedDates/notes 已是「读时聚合」的只读投影，不再作为写入路径回写。
        habitDao.upsert(habit);
    }

    /**
     * 按 id 删除单个习惯，并限定归属为当前账号。只动一行，不整表覆盖，避免并发丢更新。
     * 带 owner 过滤是数据隔离的防御措施：即便传入其它账号的 id 也删不到，不会跨账号误删。
     *
     * <p>v4 起 check_in_records 有 FK ON DELETE CASCADE，删习惯行即自动删其打卡记录。
     * 但先删磁盘上的打卡照片文件：DB 级联只清行、不清文件，行删掉后就再也枚举不到
     * 这些照片路径了，会留下孤儿图。故先读照片路径删文件，再删习惯（触发级联删记录）。</p>
     */
    public void deleteHabitById(long habitId) {
        String owner = currentUser();
        if (owner.isEmpty()) {
            return;
        }
        HabitItem target = habitDao.findById(habitId);
        if (target == null || !owner.equals(target.getOwnerUsername())) {
            return; // 不存在或非本账号：不删（数据隔离防御）
        }
        checkInRepository.deleteCheckInPhotos(habitId);
        database.runInTransaction(() -> habitDao.deleteByIdForOwner(habitId, owner));
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
        List<HabitItem> all = habitDao.getAll();
        if (all == null || all.isEmpty()) {
            return 0;
        }
        List<HabitItem> orphans = new ArrayList<>();
        for (HabitItem habit : all) {
            String owner = habit.getOwnerUsername();
            boolean orphan = owner == null || owner.isEmpty()
                    || validOwners == null || !validOwners.contains(owner);
            if (orphan) {
                habit.setOwnerUsername(claimant);
                orphans.add(habit);
            }
        }
        if (orphans.isEmpty()) {
            return 0;
        }
        database.runInTransaction(() -> habitDao.upsertAll(orphans));
        return orphans.size();
    }
}
