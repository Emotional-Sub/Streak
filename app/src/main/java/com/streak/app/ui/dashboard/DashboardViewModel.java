package com.streak.app.ui.dashboard;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.streak.app.model.HabitItem;
import com.streak.app.model.UserAccount;
import com.streak.app.storage.AppRepository;
import com.streak.app.util.AppExecutors;

import java.util.ArrayList;
import java.util.List;

/**
 * Dashboard 共享数据源（Phase C）。作用域挂在 {@code DashboardActivity} 上，
 * 四个 Fragment 用 {@code new ViewModelProvider(requireActivity()).get(DashboardViewModel.class)}
 * 拿到同一个实例，各自 observe 自己关心的 LiveData——取代旧 {@code MainActivity.refreshDashboardData()}
 * 一次重绘全部页面的做法。
 *
 * <p><b>为什么用 AndroidViewModel。</b>{@link AppRepository} 构造需要 Context；用 Application 级
 * Context 既能构造仓库，又不泄漏 Activity。仓库自身无状态可安全在 ViewModel 里长期持有。</p>
 *
 * <p><b>线程模型。</b>{@link #reload()} 把读盘（readHabits + 账号资料）放 {@code AppExecutors.diskIO}，
 * 结果用 {@code postValue} 回投主线程，观测者在主线程收到更新。旋转屏幕/页面重建时 ViewModel 存活，
 * 已加载的数据不丢，也不会因回调持有已销毁的 View 而崩。</p>
 */
public class DashboardViewModel extends AndroidViewModel {

    private final AppRepository repository;

    private final MutableLiveData<List<HabitItem>> habits = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<String> displayName = new MutableLiveData<>("");

    public DashboardViewModel(@NonNull Application application) {
        super(application);
        this.repository = new AppRepository(application);
    }

    /** 全部习惯（当前登录账号，已聚合回填打卡记录）。各页只读观测。 */
    public LiveData<List<HabitItem>> getHabits() {
        return habits;
    }

    /** 当前账号展示名（昵称优先，否则用户名）。 */
    public LiveData<String> getDisplayName() {
        return displayName;
    }

    /** 共享仓库句柄，供 Fragment 执行打卡/编辑/删除等写操作后调 {@link #reload()} 刷新。 */
    public AppRepository repository() {
        return repository;
    }

    /**
     * 后台重新加载习惯与展示名并推送给观测者。写操作（打卡/编辑/删除/导入）完成后调用，
     * 各页 observe 到新值后各自刷新自己那部分 UI。
     */
    public void reload() {
        AppExecutors.getInstance().diskIO().execute(() -> {
            List<HabitItem> loaded = repository.readHabits();
            habits.postValue(loaded);
            displayName.postValue(resolveDisplayName());
        });
    }

    private String resolveDisplayName() {
        UserAccount account = repository.getCurrentAccount();
        if (account != null && account.getDisplayName() != null
                && !account.getDisplayName().trim().isEmpty()) {
            return account.getDisplayName();
        }
        String currentUser = repository.getCurrentUser();
        return currentUser == null ? "" : currentUser;
    }
}
