package quartz;

import java.text.ParseException;
import java.util.Map;
import java.util.Properties;

import org.jetbrains.annotations.NotNull;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.DirectSchedulerFactory;
import org.quartz.simpl.SimpleThreadPool;
import org.quartz.simpl.RAMJobStore;
import utils.LogUtil;
import org.quartz.impl.matchers.GroupMatcher;

/**
 * 任务管理，请参考文档 http://www.quartz-scheduler.org/documentation
 *
 * @author dengerYang
 * @date 2021年12月27日
 */
public class QuartzManager {

    private Scheduler sched = null;
    private String instanceName;
    // 线程池大小（重建 scheduler 时使用）
    private int threadCount = 1;
    // 标记当前实例是否已经创建过调度任务，避免重复创建导致频繁提示
    private volatile boolean jobScheduled = false;
    // 全局实例注册，确保同一 instanceName 只创建一个 QuartzManager
    private static final java.util.concurrent.ConcurrentHashMap<String, QuartzManager> INSTANCES = new java.util.concurrent.ConcurrentHashMap<>();

    private QuartzManager() {
    }

    private QuartzManager(Scheduler sched, String instanceName, int threadCount) {
        this.sched = sched;
        this.instanceName = instanceName;
        this.threadCount = Math.max(1, threadCount);
    }

    /**
     * 初始化
     *
     * @param instanceName 实例名称，根据此名称识别任务资源。
     */
    public static QuartzManager getInstance(String instanceName) {
        QuartzManager exists = INSTANCES.get(instanceName);
        if (exists != null) return exists;
        try {
            QuartzManager created = getInstance(instanceName, 1);
            QuartzManager prev = INSTANCES.putIfAbsent(instanceName, created);
            if (prev != null) {
                // 另一个线程已创建实例，清理我们新创建的 scheduler
                try { created.stopJob(); } catch (Throwable ignore) {}
                return prev;
            }
            return created;
        } catch (SchedulerException e) {
            LogUtil.info("Quartz初始化失败: " + e.getMessage());
            throw new RuntimeException("QuartzManager初始化失败" + instanceName, e);
        }
    }

    public static QuartzManager getInstance(String instanceName, int threadCount) throws SchedulerException {
        int tc = Math.max(1, threadCount);
        Properties props = new Properties();
        if (instanceName != null) props.put("org.quartz.scheduler.instanceName", instanceName);
        props.put("org.quartz.threadPool.threadCount", String.valueOf(tc));
        // 防御性设置系统属性，避免类路径或外部 quartz.properties 覆盖导致 threadCount 为 0
        try {
            System.setProperty("org.quartz.threadPool.threadCount", String.valueOf(tc));
            LogUtil.info("QuartzManager.getInstance: set system org.quartz.threadPool.threadCount=" + tc);
        } catch (Throwable ignore) {}
        Scheduler sched;
        try {
            StdSchedulerFactory stdSchedulerFactory = new StdSchedulerFactory(props);
            sched = stdSchedulerFactory.getScheduler();
        } catch (SchedulerException e) {
            // Fallback: create scheduler programmatically to avoid quartz.properties issues
            LogUtil.info("StdSchedulerFactory failed: " + e.getMessage() + ", fallback to DirectSchedulerFactory");
            try {
                SimpleThreadPool tp = new SimpleThreadPool(tc, Thread.NORM_PRIORITY);
                tp.initialize();
                RAMJobStore js = new RAMJobStore();
                DirectSchedulerFactory.getInstance().createScheduler(tp, js);
                sched = DirectSchedulerFactory.getInstance().getScheduler();
            } catch (Exception ex) {
                throw new SchedulerException("无法创建 scheduler", ex);
            }
        }
        return new QuartzManager(sched, instanceName, tc);
    }

    /**
     * 检查是否符合表达式
     *
     * @param cronExpression 表达式
     * @return true表达式正确
     */
    public static boolean checkCronExpression(String cronExpression) {
        try {
            new CronExpression(cronExpression);
            return true;
        } catch (ParseException e) {
            return false;
        }
    }

    /**
     * 查询指定 instanceName 是否已经有任务被调度（不强制创建实例）
     */
    public static boolean isJobScheduledFor(String instanceName) {
        QuartzManager qm = INSTANCES.get(instanceName);
        return qm != null && qm.jobScheduled;
    }

    /**
     * 添加一个主定时任务，如果存在会替换。注意，只有运行一个任务，每次执行都会清除掉之前任务
     *
     * @param clazz          jobCLass
     * @param cronExpression cron表达式，支持;分隔
     * @param dataMap        传递给job的参数
     */
    public synchronized void runJob(Class<? extends Job> clazz, @NotNull String cronExpression, Map<? extends String, ?> dataMap) {
        int attempts = 0;
        while (attempts < 2) {
            try {
                // 如果已经有任务创建且调度器仍然可用，则跳过重复创建（避免频繁重建同一实例的任务）
                if (jobScheduled) {
                    try {
                        if (sched != null && !sched.isShutdown()) {
                            LogUtil.info("任务已存在，跳过重复创建: " + instanceName);
                            return;
                        } else {
                            // 调度器已被关闭，但标记仍为已创建，重置标记并继续创建新调度器
                            jobScheduled = false;
                        }
                    } catch (Exception ignore) {
                        jobScheduled = false;
                    }
                }

                // 确保有一个可用的 scheduler：如果为空或已关闭则重建一个新的实例
                if (sched == null || sched.isShutdown()) {
                    Properties props = new Properties();
                    if (instanceName != null) props.put("org.quartz.scheduler.instanceName", instanceName);
                    int tc = Math.max(1, this.threadCount);
                    props.put("org.quartz.threadPool.threadCount", String.valueOf(tc));
                    try {
                        System.setProperty("org.quartz.threadPool.threadCount", String.valueOf(tc));
                        LogUtil.info("QuartzManager.runJob: set system org.quartz.threadPool.threadCount=" + tc + " for instance " + instanceName);
                    } catch (Throwable ignore) {}
                    StdSchedulerFactory factory = new StdSchedulerFactory(props);
                    sched = factory.getScheduler();
                } else {
                    // 如果当前 scheduler 存在且未关闭，清除现有任务
                    try {
                        sched.clear();
                    } catch (SchedulerException clearEx) {
                        // 如果在清理时遇到 scheduler 已关闭，则尝试重建
                        try {
                            if (sched.isShutdown()) {
                                Properties props = new Properties();
                                if (instanceName != null) props.put("org.quartz.scheduler.instanceName", instanceName);
                                props.put("org.quartz.threadPool.threadCount", String.valueOf(Math.max(1, this.threadCount)));
                                StdSchedulerFactory factory = new StdSchedulerFactory(props);
                                sched = factory.getScheduler();
                            } else {
                                throw clearEx;
                            }
                        } catch (SchedulerException reinitEx) {
                            LogUtil.info("Quartz初始化失败: " + reinitEx.getMessage());
                            throw new RuntimeException("添加定时任务失败", reinitEx);
                        }
                    }
                }

                String[] split = cronExpression.split(";");
                for (int i = 0; i < split.length; i++) {
                    String cron = split[i];
                    JobDetail detail = JobBuilder.newJob(clazz).withIdentity(instanceName + i).build();
                    if (dataMap != null && !dataMap.isEmpty()) {
                        detail.getJobDataMap().putAll(dataMap);
                    }
                    String handlerName = "";
                    try { if (dataMap != null && dataMap.get(HandlerJob.KEY_HANDLER) != null) handlerName = dataMap.get(HandlerJob.KEY_HANDLER).getClass().getSimpleName(); } catch (Throwable ignore) {}
                    LogUtil.info("创建任务 [ " + cron + " ] " + handlerName);
                    sched.scheduleJob(detail, TriggerBuilder.newTrigger().withSchedule(CronScheduleBuilder.cronSchedule(cron)).build());
                }
                // 启动（如果尚未启动）
                if (!sched.isStarted()) {
                    sched.start();
                }
                jobScheduled = true;
                return;
            } catch (SchedulerException e) {
                LogUtil.info("Quartz初始化失败: " + e.getMessage());
                // 如果是线程数为0导致的异常，尝试设置系统属性并重试一次
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (attempts == 0 && msg.contains("Thread count must be > 0")) {
                    try {
                        System.setProperty("org.quartz.threadPool.threadCount", "1");
                        LogUtil.info("QuartzManager.runJob: detected invalid thread count, set system threadCount=1 and retry");
                    } catch (Throwable ignore) {}
                    // force reinit
                    try { if (sched != null) { sched.shutdown(); } } catch (Throwable ignore) {}
                    sched = null;
                    attempts++;
                    continue; // retry
                }
                throw new RuntimeException("添加定时任务失败", e);
            }
        }
    }

    /**
     * 以固定秒间隔调度任务（repeat forever）。
     * 优先考虑与 runJob 相同的幂等检查：如果已调度且 scheduler 可用则跳过重复创建。
     */
    public synchronized void runJobWithInterval(Class<? extends Job> clazz, int intervalSeconds, Map<? extends String, ?> dataMap) {
        if (intervalSeconds <= 0) throw new IllegalArgumentException("intervalSeconds must be > 0");
        try {
            if (jobScheduled) {
                try {
                    if (sched != null && !sched.isShutdown()) {
                        LogUtil.info("任务已存在（interval），跳过重复创建: " + instanceName);
                        return;
                    } else {
                        jobScheduled = false;
                    }
                } catch (Exception ignore) {
                    jobScheduled = false;
                }
            }
            if (sched == null || sched.isShutdown()) {
                Properties props = new Properties(); if (instanceName != null) props.put("org.quartz.scheduler.instanceName", instanceName);
                props.put("org.quartz.threadPool.threadCount", String.valueOf(Math.max(1, this.threadCount)));
                StdSchedulerFactory factory = new StdSchedulerFactory(props);
                sched = factory.getScheduler();
            } else {
                try { sched.clear(); } catch (SchedulerException clearEx) {
                    try { if (sched.isShutdown()) {
                        Properties props = new Properties(); if (instanceName != null) props.put("org.quartz.scheduler.instanceName", instanceName);
                        props.put("org.quartz.threadPool.threadCount", String.valueOf(Math.max(1, this.threadCount)));
                        StdSchedulerFactory factory = new StdSchedulerFactory(props);
                        sched = factory.getScheduler();
                    } else { throw clearEx; } } catch (SchedulerException reinitEx) { LogUtil.info("Quartz初始化失败: " + reinitEx.getMessage()); throw new RuntimeException("添加定时任务失败", reinitEx); }
                }
            }

            JobDetail detail = JobBuilder.newJob(clazz).withIdentity(instanceName + "_int").build();
            if (dataMap != null && !dataMap.isEmpty()) detail.getJobDataMap().putAll(dataMap);
            LogUtil.info("创建间隔任务 [ every " + intervalSeconds + "s ] " + detail.getKey());
            Trigger trigger = TriggerBuilder.newTrigger().withSchedule(SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(intervalSeconds).repeatForever()).build();
            sched.scheduleJob(detail, trigger);
            if (!sched.isStarted()) sched.start();
            jobScheduled = true;
            return;
        } catch (SchedulerException e) {
            LogUtil.info("Quartz初始化失败: " + e.getMessage());
            throw new RuntimeException("添加定时任务失败", e);
        }
    }

    public void stopJob() {
        // Make stop resilient: if scheduler already shutdown or null, just log and return
        try {
            if (sched != null) {
                try {
                    if (!sched.isShutdown()) {
                        try { sched.clear(); } catch (SchedulerException clearEx) { LogUtil.info("Quartz.clear() failed: " + clearEx.getMessage()); }
                        try { sched.shutdown(); } catch (SchedulerException shutdownEx) { LogUtil.info("Quartz.shutdown() failed: " + shutdownEx.getMessage()); }
                    } else {
                        LogUtil.info("stopJob: scheduler already shutdown for instance " + instanceName);
                    }
                } catch (Throwable t) {
                    LogUtil.info("stopJob unexpected error for instance " + instanceName + ": " + t.getMessage());
                } finally {
                    // Null out reference to allow re-creation later
                    sched = null;
                }
            }
        } catch (Throwable t) {
            LogUtil.info("stopJob outer error: " + t.getMessage());
        } finally {
            jobScheduled = false;
        }
    }

    /**
     * 更新已创建任务的 JobDataMap（仅替换与当前实例名匹配的任务），用于在运行时更新传递给 Job 的参数（例如 codes）
     */
    public void updateJobData(Map<? extends String, ?> dataMap) {
        if (dataMap == null || dataMap.isEmpty()) return;
        try {
            if (sched == null || sched.isShutdown()) {
                LogUtil.info("updateJobData: scheduler not available for " + instanceName);
                return;
            }
            for (JobKey jk : sched.getJobKeys(GroupMatcher.anyJobGroup())) {
                if (jk.getName() == null || !jk.getName().startsWith(instanceName)) continue;
                try {
                    JobDetail detail = sched.getJobDetail(jk);
                    if (detail == null) continue;
                    JobDataMap jdm = detail.getJobDataMap();
                    jdm.putAll(dataMap);
                    // 如果该 job 当前没有触发器，则在添加时需要将 job 标记为 durable
                    try {
                        java.util.List<? extends Trigger> triggers = sched.getTriggersOfJob(jk);
                        if (triggers == null || triggers.isEmpty()) {
                            // 重新构建一个可持久化的 JobDetail 并替换
                            JobDetail durable = JobBuilder.newJob(detail.getJobClass())
                                    .withIdentity(detail.getKey().getName(), detail.getKey().getGroup())
                                    .storeDurably()
                                    .usingJobData(jdm)
                                    .build();
                            sched.addJob(durable, true);
                        } else {
                            // 普通替换（触发器存在时直接替换）
                            sched.addJob(detail, true);
                        }
                    } catch (SchedulerException tse) {
                        // fallback: 若出现异常，确保以 durable 的方式重建并添加 Job，避免 "Jobs added with no trigger must be durable." 错误
                        try {
                            JobDetail durableFallback = JobBuilder.newJob(detail.getJobClass())
                                    .withIdentity(detail.getKey().getName(), detail.getKey().getGroup())
                                    .storeDurably()
                                    .usingJobData(jdm)
                                    .build();
                            sched.addJob(durableFallback, true);
                        } catch (SchedulerException ex2) {
                            // 最后兜底：尝试直接替换（如果仍然失败则由上层捕获并记录）
                            try { sched.addJob(detail, true); } catch (SchedulerException ignore) {}
                        }
                    }
                } catch (SchedulerException e) {
                    LogUtil.info("更新任务数据失败 for " + jk + ": " + e.getMessage());
                }
            }
        } catch (SchedulerException e) {
            LogUtil.info("查询 JobKeys 失败: " + e.getMessage());
        }
    }
}
