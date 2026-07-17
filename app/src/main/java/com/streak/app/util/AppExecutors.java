package com.streak.app.util;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 应用级统一线程模型（P4）。
 *
 * <p><b>为什么要统一。</b>历史上各 Activity 各自 {@code new SingleThreadExecutor()}、Receiver/Widget
 * 直接 {@code new Thread()}、到处 {@code new Handler(mainLooper)}——线程来源零散，销毁时机不一，
 * 难以保证「磁盘写串行、计算并行、UI 回主线程」的一致纪律。本类把线程收敛到三个语义明确的池：</p>
 *
 * <ul>
 *   <li>{@link #diskIO()}：<b>单线程串行</b>。Room 读写、备份、图片落盘都走它——串行化天然避免
 *       并发写冲突（与旧的「每个 Activity 一个单线程 executor」串行语义一致），只是全 App 共用一条。</li>
 *   <li>{@link #computation()}：<b>固定多线程</b>。统计聚合、二维码编解码、战报位图渲染等纯计算，
 *       可并行以缩短耗时，线程数取 CPU 核心数（至少 2）。</li>
 *   <li>{@link #mainThread()}：主线程 {@link Handler} 封装成 {@link Executor}，用于把结果回投 UI。</li>
 * </ul>
 *
 * <p><b>生命周期：与进程同寿，永不 shutdown。</b>这是应用级共享池，任一 Activity 销毁时都不得关闭它
 * （否则会掐断其它使用者）。销毁后的 UI 回调由调用方在 {@code mainThread} 回调里用
 * {@code isFinishing()/isDestroyed()} 守卫拦截——后台任务照常跑完（DB 写是幂等的，无害），
 * 只是不再触碰已销毁的界面。这也是 Android 官方 architecture-components 示例的 AppExecutors 做法。</p>
 *
 * <p><b>单例。</b>双重检查锁初始化；不持有 Context，可安全静态持有。</p>
 */
public final class AppExecutors {

    private static volatile AppExecutors instance;

    private final Executor diskIO;
    private final Executor computation;
    private final Executor mainThread;

    private AppExecutors() {
        this.diskIO = Executors.newSingleThreadExecutor(named("streak-diskIO"));
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        this.computation = Executors.newFixedThreadPool(cores, named("streak-computation"));
        this.mainThread = new MainThreadExecutor();
    }

    public static AppExecutors getInstance() {
        if (instance == null) {
            synchronized (AppExecutors.class) {
                if (instance == null) {
                    instance = new AppExecutors();
                }
            }
        }
        return instance;
    }

    /** 单线程串行池：Room / 备份 / 图片落盘等磁盘 IO。 */
    public Executor diskIO() {
        return diskIO;
    }

    /** 固定多线程池：统计 / 二维码 / 战报渲染等纯计算。 */
    public Executor computation() {
        return computation;
    }

    /** 主线程执行器：把结果回投 UI 线程。 */
    public Executor mainThread() {
        return mainThread;
    }

    /** 给池里的线程起可辨识的名字，便于调试/抓 ANR trace 时定位。 */
    private static ThreadFactory named(String prefix) {
        final AtomicInteger seq = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + seq.getAndIncrement());
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        };
    }

    private static final class MainThreadExecutor implements Executor {
        private final Handler mainHandler = new Handler(Looper.getMainLooper());

        @Override
        public void execute(Runnable command) {
            mainHandler.post(command);
        }
    }
}
