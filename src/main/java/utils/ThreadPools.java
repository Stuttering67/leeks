package utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 最小化的线程池工具，满足项目对 refresh/scheduled 执行器的依赖。
 */
public class ThreadPools {
    private static final ExecutorService REFRESH_EXECUTOR = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
    private static final ScheduledExecutorService SCHEDULED_EXECUTOR = Executors.newScheduledThreadPool(1);

    public static ExecutorService getRefreshExecutor() {
        return REFRESH_EXECUTOR;
    }

    public static ScheduledExecutorService getScheduledExecutor() {
        return SCHEDULED_EXECUTOR;
    }

    private ThreadPools() {}
}
