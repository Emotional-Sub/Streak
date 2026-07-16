package com.streak.app.data;

import androidx.annotation.Nullable;

import com.streak.app.db.HabitDao;
import com.streak.app.db.StreakDatabase;
import com.streak.app.db.UserDao;
import com.streak.app.model.UserAccount;
import com.streak.app.util.PasswordHasher;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 账号仓库（Phase B 从 {@code AppRepository} 拆出）：注册、登录校验、密码、账号资料增删改查。
 *
 * <p><b>职责边界。</b>只负责 accounts 表与凭据（PBKDF2 哈希/盐）。会话态（当前登录用户名、
 * 记住用户名、主题偏好）属 {@link AuthRepository}，本类不碰 SharedPreferences。</p>
 *
 * <p><b>当前账号来源。</b>{@link #getCurrentAccount()} 需要「当前登录用户名」，由构造时注入的
 * {@link Supplier} 提供（{@code AppRepository} 传入 {@code this::getCurrentUser}），避免与
 * 会话层循环依赖。</p>
 *
 * <p><b>改名的会话同步剥离。</b>{@link #updateAccount} 只负责账号表与「习惯归属」的原子迁移，
 * 不改会话态；改名成功后由门面（{@code AppRepository}）调 {@code AuthRepository} 同步
 * current_user/saved_username。这样账号数据与会话态各自内聚，不交叉持有对方依赖。</p>
 */
public class UserRepository {

    private final StreakDatabase database;
    private final UserDao userDao;
    private final HabitDao habitDao;
    private final Supplier<String> currentUserSupplier;

    public UserRepository(StreakDatabase database, UserDao userDao, HabitDao habitDao,
                          Supplier<String> currentUserSupplier) {
        this.database = database;
        this.userDao = userDao;
        this.habitDao = habitDao;
        this.currentUserSupplier = currentUserSupplier;
    }

    public boolean validateLogin(String username, String password) {
        List<UserAccount> accounts = loadAccounts();
        boolean migrated = false;
        boolean matched = false;
        for (UserAccount account : accounts) {
            if (!username.equals(account.getUsername())) {
                continue;
            }
            if (account.isLegacyPlaintext()) {
                // 旧明文账号：明文比对成功后立即升级为 PBKDF2 哈希。
                if (account.getPassword().equals(password)) {
                    applyHashedPassword(account, password);
                    migrated = true;
                    matched = true;
                }
            } else if (PasswordHasher.verify(password, account.getSalt(), account.getPasswordHash())) {
                matched = true;
            }
            break;
        }
        if (migrated) {
            saveAccounts(accounts);
        }
        return matched;
    }

    public String registerAccount(String username, String password) {
        if (username.trim().isEmpty() || password.trim().isEmpty()) {
            return "用户名和密码不能为空";
        }
        username = username.trim();
        List<UserAccount> accounts = loadAccounts();
        for (UserAccount account : accounts) {
            if (username.equals(account.getUsername())) {
                return "该用户名已存在";
            }
        }
        UserAccount account = new UserAccount();
        account.setUsername(username);
        applyHashedPassword(account, password);
        accounts.add(account);
        saveAccounts(accounts);
        return null;
    }

    private void applyHashedPassword(UserAccount account, String password) {
        String salt = PasswordHasher.generateSalt();
        account.setSalt(salt);
        account.setPasswordHash(PasswordHasher.hash(password, salt));
        account.setPassword(null);
    }

    /** 判断候选密码是否与账号当前密码一致（兼容旧明文账号）。 */
    private boolean isSamePassword(UserAccount account, String candidate) {
        if (account.isLegacyPlaintext()) {
            return candidate.equals(account.getPassword());
        }
        return PasswordHasher.verify(candidate, account.getSalt(), account.getPasswordHash());
    }

    @Nullable
    public UserAccount getAccount(String username) {
        for (UserAccount account : loadAccounts()) {
            if (username.equals(account.getUsername())) {
                return account;
            }
        }
        return null;
    }

    @Nullable
    public UserAccount getCurrentAccount() {
        return getAccount(currentUser());
    }

    public void updateProfile(String username, String displayName, String motto, String avatarUri) {
        List<UserAccount> accounts = loadAccounts();
        for (UserAccount account : accounts) {
            if (username.equals(account.getUsername())) {
                account.setDisplayName(displayName);
                account.setMotto(motto);
                account.setAvatarUri(avatarUri);
                break;
            }
        }
        saveAccounts(accounts);
    }

    /**
     * 编辑账号：可同时修改用户名（查重）、昵称、签名、头像、密码。
     * newPassword 为空表示不改密码。返回 null 表示成功，否则返回错误信息。
     *
     * <p><b>只管账号表与习惯归属，不改会话态。</b>改名后必须把该账号名下所有习惯的归属同步到
     * 新用户名，否则 getByOwner(新名) 会查不到旧习惯——这一步与账号表替换绑成同一事务，
     * 避免「习惯已改名、账号表写入失败」留下查不出的孤儿数据。会话态（current_user/saved_username）
     * 的同步由门面在本方法返回 null 后调 {@code AuthRepository} 处理。</p>
     */
    public String updateAccount(String oldUsername, String newUsername, String displayName,
                                String motto, String avatarUri, String newPassword) {
        if (newUsername == null || newUsername.trim().isEmpty()) {
            return "用户名不能为空";
        }
        newUsername = newUsername.trim();
        List<UserAccount> accounts = loadAccounts();
        UserAccount target = null;
        for (UserAccount account : accounts) {
            if (oldUsername.equals(account.getUsername())) {
                target = account;
            } else if (newUsername.equals(account.getUsername())) {
                return "该用户名已被占用";
            }
        }
        if (target == null) {
            return "账号不存在";
        }

        target.setUsername(newUsername);
        target.setDisplayName(displayName);
        target.setMotto(motto);
        target.setAvatarUri(avatarUri);
        if (newPassword != null && !newPassword.isEmpty()) {
            if (isSamePassword(target, newPassword)) {
                return "新密码不能与原密码相同";
            }
            applyHashedPassword(target, newPassword);
        }
        final String finalNewUsername = newUsername;
        final boolean renamed = !oldUsername.equals(finalNewUsername);
        final List<UserAccount> toSave = accounts;
        database.runInTransaction(() -> {
            if (renamed) {
                habitDao.updateOwner(oldUsername, finalNewUsername);
            }
            saveAccounts(toSave);
        });
        return null;
    }

    /**
     * 纯读取账号列表。默认账号的初始化已收敛进 {@code AppRepository.initializeStorageIfNeeded()}
     * （仅全新安装触发一次），这里不再「表空即补默认账号」，避免老用户删号后又被塞回、
     * 以及与迁移交错重复写入。
     */
    public List<UserAccount> loadAccounts() {
        List<UserAccount> accounts = userDao.getAll();
        return accounts == null ? new ArrayList<>() : accounts;
    }

    /**
     * 确保给定用户名的账号存在，缺失则补一个默认演示账号（密码 123456）。
     * 用于旧数据迁移：迁来的习惯统一归属 student，但旧账号表里未必有 student，
     * 补齐后这些习惯才有可登录的归属，不至于成为看不到的孤儿数据。
     */
    public void ensureAccountExists(String username) {
        if (username == null || username.isEmpty()) {
            return;
        }
        if (userDao.findByUsername(username) != null) {
            return;
        }
        UserAccount account = new UserAccount();
        account.setUsername(username);
        applyHashedPassword(account, "123456");
        userDao.upsert(account);
    }

    /** 整体替换账号表：清空后批量写入，事务保证一致性（等价旧的整文件覆盖语义）。 */
    public void saveAccounts(List<UserAccount> accounts) {
        userDao.replaceAll(accounts == null ? new ArrayList<>() : accounts);
    }

    /** 默认演示账号（student/123456），仅全新安装首启补种时用。 */
    public List<UserAccount> defaultAccounts() {
        UserAccount student = new UserAccount();
        student.setUsername("student");
        applyHashedPassword(student, "123456");
        List<UserAccount> defaults = new ArrayList<>();
        defaults.add(student);
        return defaults;
    }

    private String currentUser() {
        String owner = currentUserSupplier.get();
        return owner == null ? "" : owner;
    }
}
