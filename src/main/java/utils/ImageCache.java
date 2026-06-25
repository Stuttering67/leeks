package utils;

import com.intellij.ide.util.PropertiesComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 可配置的图像缓存：内存 LRU + 磁盘缓存（TTL） + 定期清理
 */
public class ImageCache {
    private static final ImageCache INSTANCE = new ImageCache();

    // 可配置项（可通过 SettingsWindow 修改）
    private volatile boolean enabled = true;
    private volatile int maxMem = 50;
    private volatile long diskTtlMs = TimeUnit.HOURS.toMillis(24);
    private volatile long cleanupIntervalMinutes = TimeUnit.DAYS.toMinutes(1);
    private volatile Path cacheDir;

    private final Map<String, byte[]> memCache;
    private ScheduledFuture<?> cleanupFuture;

    private ImageCache() {
        memCache = Collections.synchronizedMap(new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                return size() > ImageCache.this.maxMem;
            }
        });
        String tmp = System.getProperty("java.io.tmpdir");
        cacheDir = Paths.get(tmp == null ? "." : tmp, "leeks-imagecache");
        try { Files.createDirectories(cacheDir); } catch (Exception ignore) {}
        // 读取配置并启动清理任务
        reloadConfig();
    }

    public static ImageCache getInstance() { return INSTANCE; }

    public synchronized void reloadConfig() {
        try {
            PropertiesComponent pc = PropertiesComponent.getInstance();
            enabled = pc.getBoolean("key_imagecache_enabled", true);
            try { maxMem = Integer.parseInt(pc.getValue("key_imagecache_max_mem", "50")); } catch (Exception ignore) { maxMem = 50; }
            try { long h = Long.parseLong(pc.getValue("key_imagecache_ttl_hours", "24")); diskTtlMs = TimeUnit.HOURS.toMillis(Math.max(1, h)); } catch (Exception ignore) { diskTtlMs = TimeUnit.HOURS.toMillis(24); }
            try { cleanupIntervalMinutes = Long.parseLong(pc.getValue("key_imagecache_cleanup_interval_minutes", String.valueOf(TimeUnit.DAYS.toMinutes(1)))); } catch (Exception ignore) { cleanupIntervalMinutes = TimeUnit.DAYS.toMinutes(1); }
            String dir = pc.getValue("key_imagecache_dir", "");
            if (dir != null && !dir.trim().isEmpty()) {
                try {
                    Path p = Paths.get(dir.trim()); Files.createDirectories(p); cacheDir = p;
                } catch (Exception ignore) {}
            }
            // 取消旧任务
            if (cleanupFuture != null && !cleanupFuture.isCancelled()) {
                try { cleanupFuture.cancel(true); } catch (Exception ignore) {}
                cleanupFuture = null;
            }
            if (enabled && cleanupIntervalMinutes > 0) {
                cleanupFuture = ThreadPools.getScheduledExecutor().scheduleWithFixedDelay(() -> {
                    try { cleanExpiredFiles(); } catch (Exception e) { try { LogUtil.info("ImageCache cleanup failed: " + e.getMessage()); } catch (Throwable ignore) {} }
                }, 1, Math.max(1, cleanupIntervalMinutes), TimeUnit.MINUTES);
            }
        } catch (Exception e) {
            try { LogUtil.info("ImageCache reloadConfig error: " + e.getMessage()); } catch (Throwable ignore) {}
        }
    }

    public byte[] get(String url) {
        if (!enabled || url == null) return null;
        try {
            byte[] v = memCache.get(url);
            if (v != null) return v;
            Path f = cacheDir.resolve(sha256Hex(url));
            if (Files.exists(f)) {
                long last = Files.getLastModifiedTime(f).toMillis();
                if (System.currentTimeMillis() - last > diskTtlMs) {
                    try { Files.deleteIfExists(f); } catch (Exception ignore) {}
                    return null;
                }
                byte[] data = Files.readAllBytes(f);
                memCache.put(url, data);
                return data;
            }
        } catch (Exception e) {
            try { LogUtil.info("ImageCache get failed: " + e.getMessage()); } catch (Throwable ignore) {}
        }
        return null;
    }

    public void put(String url, byte[] data) {
        if (!enabled || url == null || data == null) return;
        memCache.put(url, data);
        try {
            Path dest = cacheDir.resolve(sha256Hex(url));
            Path tmp = cacheDir.resolve(sha256Hex(url) + ".tmp");
            // 异步写磁盘，避免阻塞调用线程
            ThreadPools.getRefreshExecutor().execute(() -> {
                try {
                    Files.write(tmp, data);
                    try { Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException ex) { Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING); }
                } catch (Exception e) {
                    try { LogUtil.info("ImageCache put failed: " + e.getMessage()); } catch (Throwable ignore) {}
                }
            });
        } catch (Exception e) {
            try { LogUtil.info("ImageCache scheduling put failed: " + e.getMessage()); } catch (Throwable ignore) {}
        }
    }

    public void clearMemory() { memCache.clear(); }

    private void cleanExpiredFiles() {
        try {
            if (!Files.exists(cacheDir)) return;
            long now = System.currentTimeMillis();
            Files.list(cacheDir).filter(p -> Files.isRegularFile(p)).forEach(p -> {
                try {
                    long last = Files.getLastModifiedTime(p).toMillis();
                    if (now - last > diskTtlMs) { Files.deleteIfExists(p); }
                } catch (Exception ignore) {}
            });
        } catch (Exception e) { try { LogUtil.info("ImageCache.cleanExpiredFiles error: " + e.getMessage()); } catch (Throwable ignore) {} }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
