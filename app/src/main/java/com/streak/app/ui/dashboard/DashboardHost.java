package com.streak.app.ui.dashboard;

/**
 * Dashboard 宿主回调（Phase C）。四个 Fragment 通过它把「需要离开 Dashboard 或跨页协作」的动作
 * 委派给宿主 {@code DashboardActivity}，而不硬编码依赖具体 Activity/其它页面——保持 Fragment 与
 * 宿主解耦，也便于单测替身。
 */
public interface DashboardHost {

    /**
     * 当前账号已被删除（连同其数据）。宿主应结束 Dashboard 并回到登录页。
     * 在主线程调用。
     */
    void onAccountDeleted();

    /** 主动退出登录。宿主应结束 Dashboard 并回到登录页。在主线程调用。 */
    void onLoggedOut();
}
